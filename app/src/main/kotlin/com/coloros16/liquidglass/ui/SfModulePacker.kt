package com.coloros16.liquidglass.ui

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import com.coloros16.liquidglass.log.Lg
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * SF 系统模块打包器：本机取材真身 so → sha256/ro.build 指纹/dynsym 符号表 → Magisk 模块 zip 落下载目录。
 * 全程只读取材 + 本进程写下载目录，不需要 root；缺通用件时如实回执、不扫他人文件凑数。
 */
internal object SfModulePacker {
    private const val TAG = "SfModulePacker"
    private const val REAL_SO_PATH = "/system_ext/lib64/oplusex/libdisplayppex.so"
    private const val TRAMPOLINE_DIR = "lg-sf"
    private const val TRAMPOLINE_NAME = "trampoline.so"
    private const val SERVICE_SH_NAME = "service.sh"
    private const val MODULE_ID = "lg_sf_channel"
    private const val DOWNLOAD_PATH_PREFIX = "/sdcard/Download/"
    private const val REAL_SO_MAX_BYTES = 64L * 1024 * 1024
    private const val MISSING_TRAMPOLINE_MSG = "打包件未就绪（trampoline 通用件未生成）"
    private const val MISSING_SERVICE_MSG = "打包件未就绪（开机服务脚本未生成）"

    /** 打包结果：ok=true 时 path=zip 落盘路径；失败时 message=如实回执文案。 */
    internal data class Result(
        val ok: Boolean,
        val path: String,
        val name: String,
        val sizeBytes: Long,
        val symbolCount: Int,
        val message: String,
    )

    private class PackError(msg: String) : Exception(msg)

    fun pack(context: Context, stageDir: File? = null): Result {
        Lg.i(TAG, "pack begin")
        return try {
            val trampoline = readAsset(context, TRAMPOLINE_NAME, MISSING_TRAMPOLINE_MSG)
            val serviceSh = readAsset(context, SERVICE_SH_NAME, MISSING_SERVICE_MSG)
            val installSh = installSh().toByteArray(Charsets.UTF_8)
            val real = readRealSo()
            val sha256 = sha256Hex(real)
            val fingerprint = Build.FINGERPRINT ?: "unknown"
            val symbols = DynsymParser.parse(real)
            if (symbols.isEmpty()) throw PackError("解析系统库符号失败：动态符号为空")
            val name = zipName(fingerprint)
            val entries = buildEntries(trampoline, real, sha256, fingerprint, symbols, serviceSh, installSh)
            if (stageDir != null) stageTree(entries, stageDir)
            val size = publish(context, name) { zip -> writeZip(zip, entries) }
            Lg.i(TAG, "pack ok name=$name size=$size symbols=${symbols.size} sha256=$sha256")
            Result(true, DOWNLOAD_PATH_PREFIX + name, name, size, symbols.size, "")
        } catch (e: PackError) {
            logThrottled("pack", "pack rejected: ${e.message}")
            Result(false, "", "", 0, 0, e.message ?: "打包失败")
        } catch (t: Throwable) {
            logThrottled("pack", "pack failed: ${t.javaClass.simpleName}: ${t.message}")
            Lg.w(TAG, "pack failed", t)
            Result(false, "", "", 0, 0, "打包失败：${t.message ?: t.javaClass.simpleName}")
        }
    }

    // ---------- 取材 ----------

    private fun readAsset(context: Context, name: String, missingMsg: String): ByteArray {
        val listed = try {
            context.assets.list(TRAMPOLINE_DIR) ?: emptyArray()
        } catch (t: Throwable) {
            throw PackError("读取打包件失败：${t.message ?: t.javaClass.simpleName}")
        }
        if (name !in listed) throw PackError(missingMsg)
        val bytes = try {
            context.assets.open("$TRAMPOLINE_DIR/$name").use { it.readBytes() }
        } catch (e: FileNotFoundException) {
            throw PackError(missingMsg)
        } catch (t: Throwable) {
            throw PackError("读取打包件失败：${t.message ?: t.javaClass.simpleName}")
        }
        if (bytes.isEmpty()) throw PackError(missingMsg)
        return bytes
    }

    private fun readRealSo(): ByteArray {
        if (isRealSoOverlaid()) {
            throw PackError("取材路径正被挂载覆盖（调试残留挂载），请先还原挂载再打包")
        }
        val file = File(REAL_SO_PATH)
        if (!file.isFile) {
            throw PackError("非 ColorOS 设备，不支持（特征文件 $REAL_SO_PATH 不存在）")
        }
        val bytes = try {
            if (file.length() > REAL_SO_MAX_BYTES) throw PackError("读取系统库失败：文件异常过大")
            file.readBytes()
        } catch (e: PackError) {
            throw e
        } catch (t: Throwable) {
            throw PackError("读取系统库失败：${t.message ?: t.javaClass.simpleName}")
        }
        if (bytes.isEmpty()) throw PackError("读取系统库失败：文件为空")
        return bytes
    }

    private fun isRealSoOverlaid(): Boolean = try {
        File("/proc/self/mounts").readLines().any { line ->
            val parts = line.trim().split(Regex("\\s+"))
            parts.size >= 2 && parts[1] == REAL_SO_PATH
        }
    } catch (t: Throwable) {
        Lg.w(TAG, "read mounts failed: ${t.javaClass.simpleName}")
        false
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hex = "0123456789abcdef"
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xff
            sb.append(hex[v shr 4]).append(hex[v and 0xf])
        }
        return sb.toString()
    }

    private fun zipName(fingerprint: String): String {
        val safe = fingerprint.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_')
        return "lg-sf-channel-${safe.ifBlank { "unknown" }}.zip"
    }

    // ---------- zip 组装 ----------

    private fun buildEntries(
        trampoline: ByteArray,
        real: ByteArray,
        sha256: String,
        fingerprint: String,
        symbols: List<DynsymParser.Sym>,
        serviceSh: ByteArray,
        installSh: ByteArray,
    ): List<Pair<String, ByteArray>> {
        val symbolsTxt = buildString {
            append("# lg-sf-channel symbols v1\n")
            append("# source=").append(REAL_SO_PATH).append('\n')
            append("# sha256=").append(sha256).append('\n')
            append("# count=").append(symbols.size).append('\n')
            append("# columns: name<TAB>bind<TAB>type\n")
            for (s in symbols) {
                append(s.name).append('\t').append(s.bind).append('\t').append(s.type).append('\n')
            }
        }
        val fingerprintTxt = buildString {
            append("ro.build.fingerprint=").append(fingerprint).append('\n')
            append("real.so.path=").append(REAL_SO_PATH).append('\n')
            append("real.so.sha256=").append(sha256).append('\n')
            append("real.so.size=").append(real.size).append('\n')
            append("symbols.count=").append(symbols.size).append('\n')
        }
        return listOf(
            "META-INF/com/google/android/update-binary" to UPDATE_BINARY.toByteArray(Charsets.UTF_8),
            "META-INF/com/google/android/updater-script" to UPDATER_SCRIPT.toByteArray(Charsets.UTF_8),
            "module.prop" to moduleProp().toByteArray(Charsets.UTF_8),
            "system/system_ext/lib64/oplusex/libdisplayppex.so" to trampoline,
            "system/system_ext/lib64/oplusex/libdisplayppex.real.so" to real,
            "symbols.txt" to symbolsTxt.toByteArray(Charsets.UTF_8),
            "fingerprint.txt" to fingerprintTxt.toByteArray(Charsets.UTF_8),
            "service.sh" to serviceSh,
            "post-fs-data.sh" to POST_FS_DATA_SH.toByteArray(Charsets.UTF_8),
            "install.sh" to installSh,
        )
    }

    private fun moduleProp(): String = """
        id=$MODULE_ID
        name=LG SF Channel
        version=v1.0
        versionCode=1
        author=AYwlilwYA
        description=系统级液态玻璃通道
    """.trimIndent() + "\n"

    /** 安装脚本（打包时动态生成，唯一源）：root 直装与手动安装（解压 zip 后 sh 执行）同一份。 */
    private fun installSh(): String = """
        #!/system/bin/sh
        # 复制模块树到模块目录：ColorOS 校验 → 自拷贝守卫 → 复制 → 权限 → 清安装标志
        # 手动安装：解压 zip 后 root 执行 sh <解压目录>/install.sh
        SRC=${'$'}(cd "${'$'}(dirname "${'$'}0")" && pwd)
        MODDIR=${'$'}{LG_MODDIR:-/data/adb/modules/$MODULE_ID}
        FEATURE=${'$'}{LG_FEATURE_PATH:-$REAL_SO_PATH}

        if [ ! -f "${'$'}SRC/module.prop" ] || [ ! -f "${'$'}SRC/system/system_ext/lib64/oplusex/libdisplayppex.so" ]; then
          echo "SRC_BAD"
          exit 1
        fi
        if [ ! -f "${'$'}FEATURE" ]; then
          echo "NOT_COLOROS"
          exit 1
        fi
        case "${'$'}SRC" in
          "${'$'}MODDIR"|"${'$'}MODDIR"/*) echo "SELF_COPY"; exit 1 ;;
        esac

        rm -rf "${'$'}MODDIR" || { echo RM_FAIL; exit 1; }
        mkdir -p "${'$'}MODDIR" || { echo MKDIR_FAIL; exit 1; }
        cp -rf "${'$'}SRC"/. "${'$'}MODDIR"/ || { echo COPY_FAIL; exit 1; }
        chown -R root:root "${'$'}MODDIR" 2>/dev/null
        find "${'$'}MODDIR" -type f -exec chmod 644 {} + 2>/dev/null
        for f in "${'$'}MODDIR"/*.sh; do [ -f "${'$'}f" ] && chmod 755 "${'$'}f"; done
        chmod 755 "${'$'}MODDIR"
        rm -f "${'$'}MODDIR/disable" "${'$'}MODDIR/remove" "${'$'}MODDIR/update"
        test -f "${'$'}MODDIR/module.prop" && echo INSTALLED || echo MISSING
    """.trimIndent() + "\n"

    private fun writeZip(zip: ZipOutputStream, entries: List<Pair<String, ByteArray>>) {
        for ((name, data) in entries) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(data)
            zip.closeEntry()
        }
    }

    /** 把 zip 条目解出成模块目录树（跳过安装器头 META-INF），供 root 直装复制。 */
    private fun stageTree(entries: List<Pair<String, ByteArray>>, dest: File) {
        dest.deleteRecursively()
        entries.forEach { (entryName, bytes) ->
            if (entryName.startsWith("META-INF/")) return@forEach
            val out = File(dest, entryName)
            out.parentFile?.mkdirs()
            out.writeBytes(bytes)
        }
        Lg.i(TAG, "stage tree -> ${dest.absolutePath} entries=${entries.size}")
    }

    // ---------- 下载目录发布 ----------

    private fun publish(context: Context, name: String, fill: (ZipOutputStream) -> Unit): Long {
        if (Build.VERSION.SDK_INT < 29) {
            throw PackError("系统版本过低，需要 Android 10 及以上才能写入下载目录")
        }
        val resolver = context.contentResolver
        try {
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf("_id"),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf(name),
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    resolver.delete(
                        ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id),
                        null, null,
                    )
                }
            }
        } catch (t: Throwable) {
            Lg.w(TAG, "clear old zip failed: ${t.javaClass.simpleName}")
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = try {
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        } catch (t: Throwable) {
            throw PackError("写入下载目录失败：${t.message ?: t.javaClass.simpleName}")
        } ?: throw PackError("写入下载目录失败：创建下载条目被拒")
        var written = 0L
        try {
            val out = resolver.openOutputStream(uri, "w")
                ?: throw PackError("写入下载目录失败：打开写入流被拒")
            val counting = object : OutputStream() {
                override fun write(b: Int) {
                    out.write(b)
                    written++
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    out.write(b, off, len)
                    written += len
                }

                override fun flush() = out.flush()

                override fun close() = out.close()
            }
            ZipOutputStream(BufferedOutputStream(counting)).use { zip -> fill(zip) }
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null, null,
            )
        } catch (e: PackError) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw PackError("写入下载目录失败：${t.message ?: t.javaClass.simpleName}")
        }
        return written
    }

    // ---------- 节流日志 ----------

    private val logAt = ConcurrentHashMap<String, Long>()

    private fun logThrottled(key: String, msg: String, intervalMs: Long = 2000L) {
        val now = SystemClock.uptimeMillis()
        val last = logAt[key]
        if (last != null && now - last < intervalMs) return
        logAt[key] = now
        Lg.w(TAG, msg)
    }

    // ---------- 模块包固定内容 ----------

    private const val UPDATER_SCRIPT = "#MAGISK\n"

    private val UPDATE_BINARY = """
        #!/sbin/sh

        #################
        # Initialization
        #################

        umask 022

        ui_print() { echo "$1"; }

        require_new_magisk() {
          ui_print "*******************************"
          ui_print " Please install Magisk v20.4+! "
          ui_print "*******************************"
          exit 1
        }

        #########################
        # Load util_functions.sh
        #########################

        OUTFD=$2
        ZIPFILE=$3

        mount /data 2>/dev/null

        [ -f /data/adb/magisk/util_functions.sh ] || require_new_magisk
        . /data/adb/magisk/util_functions.sh
        [ ${'$'}MAGISK_VER_CODE -lt 20400 ] && require_new_magisk

        install_module
        exit 0
    """.trimIndent() + "\n"

    private val POST_FS_DATA_SH = """
        #!/system/bin/sh
        # 开机前置：读模块自家 state 判装载/拒载 → 放行=树内校验交 overlay；拒载=下掉树内两 so 露原厂
        # 只读不写 state（唯一拒载条件=disabled；单次意外永不关门）；零 bind、零 tmp 接触；日志走 logcat（非持久）
        # 树根=system/ 前缀（APatch 挂载只以 system/ 为根）；真身槽=自造名 libdisplayppex.real.so（原厂无此名，三台通吃）
        # 非 ColorOS 拒载第一关：原厂特征路径不存在=不是 ColorOS 系（判据=特征文件，禁用 brand——nabu brand=Xiaomi 也跑 ColorOS）
        PATH=/system/bin:${'$'}PATH
        MODDIR=${'$'}{LG_MODDIR:-/data/adb/modules/lg_sf_channel}
        STATE=${'$'}{LG_STATE_FILE:-${'$'}MODDIR/state}
        TREE=${'$'}MODDIR/system/system_ext/lib64/oplusex
        TRAMP=${'$'}TREE/libdisplayppex.so
        REAL=${'$'}TREE/libdisplayppex.real.so
        FEATURE=${'$'}{LG_FEATURE_PATH:-/system_ext/lib64/oplusex/libdisplayppex.so}
        lg_log() { log -p w -t LgSfPostFs "$1"; }

        if [ ! -f "${'$'}FEATURE" ]; then
          lg_log "非 ColorOS（特征缺失 ${'$'}FEATURE）→ 拒载"
          mv -f "${'$'}TRAMP" "${'$'}TRAMP.bak" 2>/dev/null
          mv -f "${'$'}REAL" "${'$'}REAL.bak" 2>/dev/null
          exit 0
        fi

        state=$(head -n1 "${'$'}STATE" 2>/dev/null)
        status=${'$'}{state%% *}
        allow=1
        case "${'$'}status" in
          disabled)
            allow=0
            lg_log "state=disabled 拒载"
            ;;
          healthy)
            lg_log "state=${'$'}state 放行"
            ;;
          suspect)
            lg_log "state=suspect 放行（单次意外不关门，本轮见真章）"
            ;;
          booting)
            lg_log "state=booting 放行（残留兜底）"
            ;;
          "")
            lg_log "state 缺失=首装/重置 放行"
            ;;
          *)
            lg_log "state 畸形='${'$'}state' 放行（不关门）"
            ;;
        esac

        if [ "${'$'}allow" = "1" ]; then
          for f in "${'$'}TRAMP" "${'$'}REAL"; do
            if [ ! -f "${'$'}f" ] && [ -f "${'$'}f.bak" ]; then
              mv -f "${'$'}f.bak" "${'$'}f" && lg_log "树内件自 bak 恢复"
            fi
          done
          if [ -f "${'$'}MODDIR/module.prop" ] && [ -f "${'$'}TRAMP" ] && [ -f "${'$'}REAL" ]; then
            lg_log "树校验通过（module.prop+两 so 在位）交 overlay"
            exit 0
          fi
          mv -f "${'$'}TRAMP" "${'$'}TRAMP.bak" 2>/dev/null
          mv -f "${'$'}REAL" "${'$'}REAL.bak" 2>/dev/null
          lg_log "树校验失败 → 回滚拒载安全态"
          exit 0
        else
          mv -f "${'$'}TRAMP" "${'$'}TRAMP.bak" 2>/dev/null
          mv -f "${'$'}REAL" "${'$'}REAL.bak" 2>/dev/null
          lg_log "拒载：树内两 so 下掉，overlay 露原厂"
          exit 0
        fi
    """.trimIndent() + "\n"

}

/**
 * ELF 动态符号表解析：只认小端 ELF32/64，取已定义符号（排除 SECTION/FILE 类型）。
 * 失败一律抛 IllegalArgumentException，由调用方转成如实回执。
 */
private object DynsymParser {

    internal data class Sym(val name: String, val bind: String, val type: String)

    private const val SHT_SYMTAB = 2
    private const val SHT_STRTAB = 3
    private const val SHT_DYNSYM = 11
    private const val MAX_SECTIONS = 4096
    private const val MAX_SYMBOLS = 1_000_000L

    fun parse(bytes: ByteArray): List<Sym> {
        if (bytes.size < 52) throw IllegalArgumentException("文件小于 ELF 头")
        if (bytes[0] != 0x7f.toByte() || bytes[1] != 'E'.code.toByte() ||
            bytes[2] != 'L'.code.toByte() || bytes[3] != 'F'.code.toByte()
        ) {
            throw IllegalArgumentException("缺少 ELF 魔数")
        }
        val elf64 = when (bytes[4].toInt() and 0xff) {
            2 -> true
            1 -> false
            else -> throw IllegalArgumentException("ELF class 异常")
        }
        if ((bytes[5].toInt() and 0xff) != 1) throw IllegalArgumentException("仅支持小端字节序")

        val headerMin = if (elf64) 64 else 52
        if (bytes.size < headerMin) throw IllegalArgumentException("文件小于 ELF 头")

        val shoff: Long
        val shentsize: Int
        val shnum: Int
        if (elf64) {
            shoff = u64(bytes, 0x28)
            shentsize = u16(bytes, 0x3a)
            shnum = u16(bytes, 0x3c)
        } else {
            shoff = u32(bytes, 0x20)
            shentsize = u16(bytes, 0x2e)
            shnum = u16(bytes, 0x30)
        }
        if (shnum < 1 || shnum > MAX_SECTIONS) throw IllegalArgumentException("节表数量异常 shnum=$shnum")
        val minShEnt = if (elf64) 64 else 40
        if (shentsize < minShEnt) throw IllegalArgumentException("节表项大小异常 shentsize=$shentsize")
        need(bytes, shoff, shnum.toLong() * shentsize)

        var dynOff = -1L
        var dynSize = 0L
        var dynEnt = 0L
        var dynLink = -1
        var symOff = -1L
        var symSize = 0L
        var symEnt = 0L
        var symLink = -1
        for (i in 0 until shnum) {
            val base = shoff + i.toLong() * shentsize
            val type = u32(bytes, base + 4).toInt()
            val off: Long
            val size: Long
            val link: Int
            val ent: Long
            if (elf64) {
                off = u64(bytes, base + 0x18)
                size = u64(bytes, base + 0x20)
                link = u32(bytes, base + 0x28).toInt()
                ent = u64(bytes, base + 0x38)
            } else {
                off = u32(bytes, base + 16)
                size = u32(bytes, base + 20)
                link = u32(bytes, base + 24).toInt()
                ent = u32(bytes, base + 36)
            }
            when (type) {
                SHT_DYNSYM -> {
                    if (dynOff < 0) {
                        dynOff = off
                        dynSize = size
                        dynEnt = ent
                        dynLink = link
                    }
                }
                SHT_SYMTAB -> {
                    if (symOff < 0) {
                        symOff = off
                        symSize = size
                        symEnt = ent
                        symLink = link
                    }
                }
            }
        }
        val (useOff, useSize, useEnt, useLink) = if (dynOff >= 0) {
            Quadruple(dynOff, dynSize, dynEnt, dynLink)
        } else if (symOff >= 0) {
            Quadruple(symOff, symSize, symEnt, symLink)
        } else {
            throw IllegalArgumentException("ELF 无动态符号表")
        }
        if (useLink < 0 || useLink >= shnum) throw IllegalArgumentException("符号串表下标异常")
        val strBase = shoff + useLink.toLong() * shentsize
        if (u32(bytes, strBase + 4).toInt() != SHT_STRTAB) throw IllegalArgumentException("符号串表类型异常")
        val strOff: Long
        val strSize: Long
        if (elf64) {
            strOff = u64(bytes, strBase + 0x18)
            strSize = u64(bytes, strBase + 0x20)
        } else {
            strOff = u32(bytes, strBase + 16)
            strSize = u32(bytes, strBase + 20)
        }
        need(bytes, strOff, strSize)
        need(bytes, useOff, useSize)

        val entSize = when {
            useEnt > 0 && useEnt >= (if (elf64) 24 else 16) -> useEnt
            elf64 -> 24L
            else -> 16L
        }
        val count = useSize / entSize
        if (count > MAX_SYMBOLS) throw IllegalArgumentException("符号数量异常 count=$count")
        val strtab = bytes.copyOfRange(strOff.toInt(), (strOff + strSize).toInt())
        val infoAt = if (elf64) 4 else 12
        val shndxAt = if (elf64) 6 else 14
        val byName = LinkedHashMap<String, Sym>()
        for (i in 0 until count) {
            val e = useOff + i * entSize
            val stName = u32(bytes, e).toInt()
            val stInfo = u8(bytes, e + infoAt)
            val stShndx = u16(bytes, e + shndxAt)
            if (stShndx == 0) continue
            val typeCode = stInfo and 0x0f
            if (typeCode == 3 || typeCode == 4) continue
            if (stName <= 0 || stName >= strtab.size) continue
            val name = cstr(strtab, stName) ?: continue
            if (name.isEmpty() || name in byName) continue
            byName[name] = Sym(name, bindName(stInfo shr 4), typeName(typeCode))
        }
        return byName.values.sortedBy { it.name }
    }

    private data class Quadruple(val off: Long, val size: Long, val ent: Long, val link: Int)

    private fun bindName(bind: Int): String = when (bind) {
        0 -> "local"
        1 -> "global"
        2 -> "weak"
        else -> "bind$bind"
    }

    private fun typeName(type: Int): String = when (type) {
        0 -> "notype"
        1 -> "object"
        2 -> "func"
        5 -> "common"
        6 -> "tls"
        10 -> "ifunc"
        else -> "type$type"
    }

    private fun cstr(strtab: ByteArray, off: Int): String? {
        var end = off
        while (end < strtab.size && strtab[end] != 0.toByte()) end++
        if (end == off) return null
        return String(strtab, off, end - off, Charsets.UTF_8)
    }

    private fun need(bytes: ByteArray, off: Long, len: Long) {
        if (off < 0 || len < 0 || off + len > bytes.size.toLong()) {
            throw IllegalArgumentException("ELF 偏移越界 off=$off len=$len")
        }
    }

    private fun u8(bytes: ByteArray, off: Long): Int {
        need(bytes, off, 1)
        return bytes[off.toInt()].toInt() and 0xff
    }

    private fun u16(bytes: ByteArray, off: Long): Int {
        need(bytes, off, 2)
        val o = off.toInt()
        return (bytes[o].toInt() and 0xff) or ((bytes[o + 1].toInt() and 0xff) shl 8)
    }

    private fun u32(bytes: ByteArray, off: Long): Long {
        need(bytes, off, 4)
        val o = off.toInt()
        var v = 0L
        for (i in 3 downTo 0) v = (v shl 8) or (bytes[o + i].toLong() and 0xff)
        return v
    }

    private fun u64(bytes: ByteArray, off: Long): Long {
        need(bytes, off, 8)
        val o = off.toInt()
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (bytes[o + i].toLong() and 0xff)
        return v
    }
}
