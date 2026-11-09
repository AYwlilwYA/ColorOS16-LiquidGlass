// SF 系统模块通用装载件：占槽纯转发 + 反 bootloop 状态机 + 引擎门禁金丝雀（logcat tag=LG-TRAMPOLINE）
// 装载层：DT_NEEDED 绝对路径把真身拉进本库依赖链，dlsym 经本句柄直达真身符号；本文件零渲染逻辑
// 状态文件：/data/local/tmp/lg_sf_tramp.state 单行格式 "<status> <boot_id> <grants>"
//   status ∈ booting|healthy|disabled；grants = 本 boot 已放行装载次数；disabled = 锁定到手动重置
// 门禁：装载期状态判定写 g_gate（默认关）；引擎首次需要时调 lg_glass_arm_engine（单飞，失败本会话永久）
// 护栏：引擎错误计数超阈永久熔断；金丝雀 = 编译探针 shader 自检，不过 = 本会话纯转发
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <atomic>
#ifndef LG_HOST_TEST
#include <android/log.h>
#endif

#define LG_TRAMP_VER "1"
#define TAG "LG-TRAMPOLINE"

#define REAL_PATH "/system_ext/lib64/oplusex/libOplusDualHeadPhoneEx.so"
#define STATE_PATH "/data/local/tmp/lg_sf_tramp.state"

#define LG_ERR_TRIP 20         /* 引擎单会话错误数熔断阈值 */
#define LG_LOG_WINDOW_MS 60000 /* 同 key 节流窗口 */
#define LG_MAX_GRANTS_BOOTING 1 /* booting 态只放行首次装载，再见到 booting = 未确认崩溃 */
#define LG_MAX_GRANTS_HEALTHY 3 /* healthy 态本 boot 放行上限，容忍少量重启 */

static const char *g_state_path = STATE_PATH;
const char *g_test_boot_id; /* 非空时覆盖 boot_id 读取 */
static int g_load_calls;

static std::atomic<int> g_gate{0};   /* 1=状态机放行引擎 */
static std::atomic<int> g_arm{0};    /* 0=未尝试 1=已武装 2=本会话拒绝 */
static std::atomic<int> g_guard_err{0};
static std::atomic<int> g_guard_trip{0};

#ifdef LG_HOST_TEST
char g_test_log[16384];
#endif

/* ---------- 日志（里程碑直打；守卫类按 key 60s 节流） ---------- */
static long lg_now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long)ts.tv_sec * 1000L + ts.tv_nsec / 1000000L;
}

static void lg_logf(const char *fmt, ...) {
    char buf[512];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    if (n <= 0) return;
#ifdef LG_HOST_TEST
    fprintf(stderr, "%s: %s\n", TAG, buf);
    size_t used = strlen(g_test_log);
    snprintf(g_test_log + used, sizeof(g_test_log) - used, "%s\n", buf);
#else
    __android_log_print(ANDROID_LOG_INFO, TAG, "%s", buf);
#endif
}

struct lg_thr_slot {
    char key[32];
    long ms;
};
static struct lg_thr_slot g_thr[8];

static void lg_log_throttled(const char *key, const char *fmt, ...) {
    long now = lg_now_ms();
    struct lg_thr_slot *slot = NULL;
    for (int i = 0; i < 8; i++) {
        if (g_thr[i].key[0] && strcmp(g_thr[i].key, key) == 0) {
            slot = &g_thr[i];
            break;
        }
    }
    if (!slot) {
        for (int i = 0; i < 8; i++) {
            if (!g_thr[i].key[0]) {
                slot = &g_thr[i];
                break;
            }
        }
        if (!slot) slot = &g_thr[0];
        strncpy(slot->key, key, sizeof(slot->key) - 1);
        slot->key[sizeof(slot->key) - 1] = 0;
        slot->ms = 0;
    }
    if (slot->ms && now - slot->ms < LG_LOG_WINDOW_MS) return;
    slot->ms = now;
    char body[400];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(body, sizeof(body), fmt, ap);
    va_end(ap);
    lg_logf("%s", body);
}

/* ---------- 状态文件读写与判定 ---------- */
struct lg_state {
    char status[16];
    char bid[80];
    long grants;
};

struct lg_plan {
    int grant;      /* 1=放行（随后必须成功写出装载标记，否则回落锁定） */
    int lock_write; /* 1=把状态文件写成 disabled */
    const char *reason;
};

static int lg_boot_id(char *out, size_t n) {
    if (g_test_boot_id && g_test_boot_id[0]) {
        snprintf(out, n, "%s", g_test_boot_id);
        return 0;
    }
    int fd = open("/proc/sys/kernel/random/boot_id", O_RDONLY);
    if (fd < 0) return -1;
    ssize_t k = read(fd, out, n - 1);
    close(fd);
    if (k <= 0) return -1;
    out[k] = 0;
    while (k > 0 && (out[k - 1] == '\n' || out[k - 1] == ' ')) out[--k] = 0;
    return 0;
}

/* 读状态文件；返回 0=读到（status 可为空串） -1=读失败（errno 保留） */
static int lg_state_read(struct lg_state *st) {
    memset(st, 0, sizeof(*st));
    int fd = open(g_state_path, O_RDONLY);
    if (fd < 0) return -1;
    char line[192];
    ssize_t k = read(fd, line, sizeof(line) - 1);
    int saved = errno;
    close(fd);
    if (k < 0) {
        errno = saved;
        return -1;
    }
    line[k] = 0;
    char *nl = strpbrk(line, "\r\n");
    if (nl) *nl = 0;
    if (line[0] == 0) return 0;
    char *sp1 = strchr(line, ' ');
    if (!sp1) {
        snprintf(st->status, sizeof(st->status), "%.15s", line);
        return 0;
    }
    *sp1 = 0;
    snprintf(st->status, sizeof(st->status), "%.15s", line);
    char *p2 = sp1 + 1;
    char *sp2 = strchr(p2, ' ');
    if (!sp2) {
        snprintf(st->bid, sizeof(st->bid), "%.79s", p2);
        return 0;
    }
    *sp2 = 0;
    snprintf(st->bid, sizeof(st->bid), "%.79s", p2);
    st->grants = strtol(sp2 + 1, NULL, 10);
    return 0;
}

static int lg_state_write(const char *status, const char *bid, long grants, int *err_out) {
    char buf[192];
    int n = snprintf(buf, sizeof(buf), "%s %s %ld\n", status, bid, grants);
    if (n <= 0 || n >= (int)sizeof(buf)) {
        if (err_out) *err_out = EINVAL;
        return 0;
    }
    int fd = open(g_state_path, O_WRONLY | O_CREAT | O_TRUNC, 0666);
    if (fd < 0) {
        if (err_out) *err_out = errno;
        return 0;
    }
    ssize_t w = write(fd, buf, (size_t)n);
    int saved = errno;
    close(fd);
    if (w != n) {
        if (err_out) *err_out = w < 0 ? saved : EIO;
        return 0;
    }
    if (err_out) *err_out = 0;
    return 1;
}

static struct lg_plan lg_state_decide(const struct lg_state *st, const char *cur_bid) {
    struct lg_plan p = {0, 0, ""};
    const char *s = st->status;
    if (s[0] == 0) {
        p.grant = 1;
        p.reason = "fresh";
        return p;
    }
    if (strcmp(s, "disabled") == 0) {
        p.reason = "locked-disabled";
        return p;
    }
    if (strcmp(s, "booting") != 0 && strcmp(s, "healthy") != 0) {
        p.lock_write = 1;
        p.reason = "state-corrupt";
        return p;
    }
    int same_boot = strcmp(st->bid, cur_bid) == 0;
    if (!same_boot) {
        if (strcmp(s, "healthy") == 0) {
            p.grant = 1;
            p.reason = "prev-boot-healthy";
        } else {
            p.lock_write = 1;
            p.reason = "prev-boot-unclean";
        }
        return p;
    }
    long limit = (strcmp(s, "healthy") == 0) ? LG_MAX_GRANTS_HEALTHY : LG_MAX_GRANTS_BOOTING;
    if (st->grants >= limit) {
        p.lock_write = 1;
        p.reason = (strcmp(s, "healthy") == 0) ? "restart-budget" : "unconfirmed-restart";
        return p;
    }
    p.grant = 1;
    p.reason = (strcmp(s, "healthy") == 0) ? "same-boot-restart" : "boot-rearm";
    return p;
}

/* 装载期状态机：判定 + 落盘，返回 1=放行 */
static int lg_state_run(void) {
    char cur_bid[80];
    if (lg_boot_id(cur_bid, sizeof(cur_bid)) != 0) {
        snprintf(cur_bid, sizeof(cur_bid), "unknown");
        lg_log_throttled("bootid", "%s WARN boot_id-unreadable", TAG);
    }
    struct lg_state st;
    if (lg_state_read(&st) != 0) {
        if (errno == ENOENT) {
            memset(&st, 0, sizeof(st));
        } else {
            g_gate.store(0);
            lg_logf("%s STATE prev=? decision=deny reason=state-unreadable errno=%d", TAG, errno);
            return 0;
        }
    }
    struct lg_plan p = lg_state_decide(&st, cur_bid);
    if (p.grant) {
        const char *st_out;
        long grants;
        if (st.status[0] == 0 ||
            (strcmp(st.status, "healthy") == 0 && strcmp(st.bid, cur_bid) != 0)) {
            st_out = "booting";
            grants = 1;
        } else if (strcmp(st.status, "healthy") == 0) {
            st_out = "healthy";
            grants = st.grants + 1;
        } else {
            st_out = "booting";
            grants = st.grants + 1;
        }
        int err = 0;
        if (!lg_state_write(st_out, cur_bid, grants, &err)) {
            g_gate.store(0);
            lg_logf("%s STATE prev=%s decision=deny reason=state-write-fail errno=%d", TAG,
                    st.status[0] ? st.status : "empty", err);
            return 0;
        }
        g_gate.store(1);
        lg_logf("%s STATE prev=%s grants=%ld decision=grant new=%s grants=%ld reason=%s", TAG,
                st.status[0] ? st.status : "empty", st.grants, st_out, grants, p.reason);
        return 1;
    }
    if (p.lock_write) {
        int err = 0;
        if (!lg_state_write("disabled", cur_bid, st.grants, &err)) {
            lg_log_throttled("lockw", "%s WARN lock-write-fail reason=%s errno=%d", TAG,
                             p.reason, err);
        }
    }
    g_gate.store(0);
    lg_logf("%s STATE prev=%s grants=%ld decision=deny reason=%s", TAG,
            st.status[0] ? st.status : "empty", st.grants, p.reason);
    return 0;
}

/* ---------- 金丝雀与引擎门禁 ---------- */
#ifdef LG_HOST_TEST
static int g_test_sym_ok = 1;
static int lg_resolve_engine_syms(void) { return g_test_sym_ok ? 0 : 1; }
static int lg_canary_probe(const char **step) {
    *step = "host-fake";
    const char *e = getenv("LG_TEST_CANARY");
    return (e && strcmp(e, "fail") == 0) ? 0 : 1;
}
#else
static const char *kProbeSkSL = "half4 main(float2 coord) { return half4(0.5,0.5,0.5,1.0); }";

struct lg_canary_syms {
    void *make_for_shader;
    void *str_c1;
    void *str_d1;
};
static struct lg_canary_syms C;

#define M_MAKE_FOR_SHADER "_ZN15SkRuntimeEffect13MakeForShaderE8SkStringRKNS_7OptionsE"
#define M_STR_C1 "_ZN8SkStringC1EPKc"
#define M_STR_D1 "_ZN8SkStringD1Ev"

static void sk_unref(void *obj) {
    if (!obj) return;
    int old = __atomic_fetch_sub((int *)((char *)obj + 8), 1, __ATOMIC_ACQ_REL);
    if (old == 1) {
        void *vt = *(void **)obj;
        void (*dtor)(void *) = (void (*)(void *))((void **)vt)[2];
        dtor(obj);
    }
}

#define X8CLOB "x0","x1","x2","x3","x4","x5","x6","x7","x8","x9","x10","x11","x12","x13","x14","x15","x16","x17","x30", \
               "v0","v1","v2","v3","v4","v5","v6","v7","v8","v9","v10","v11","v12","v13","v14","v15", \
               "v16","v17","v18","v19","v20","v21","v22","v23","v24","v25","v26","v27","v28","v29","v30","v31", \
               "memory","cc"

static inline __attribute__((always_inline)) void x8_call(void *ret, void *fn,
                                                          const void **args, int n) {
    struct {
        void *fn;
        void *ret;
        void *args[8];
    } s;
    s.fn = fn;
    s.ret = ret;
    for (int i = 0; i < 8; i++) s.args[i] = (args && i < n) ? (void *)args[i] : NULL;
    __asm__ volatile(
        "mov x9, %0\n\t"
        "ldr x10, [x9]\n\t"
        "ldr x11, [x9, #8]\n\t"
        "ldp x0, x1, [x9, #16]\n\t"
        "ldp x2, x3, [x9, #32]\n\t"
        "ldp x4, x5, [x9, #48]\n\t"
        "ldp x6, x7, [x9, #64]\n\t"
        "mov x8, x11\n\t"
        "blr x10\n\t"
        ::"r"(&s)
        : X8CLOB);
}

static int lg_resolve_engine_syms(void) {
    C.make_for_shader = dlsym(RTLD_DEFAULT, M_MAKE_FOR_SHADER);
    C.str_c1 = dlsym(RTLD_DEFAULT, M_STR_C1);
    C.str_d1 = dlsym(RTLD_DEFAULT, M_STR_D1);
    int miss = 0;
    if (!C.make_for_shader) miss++;
    if (!C.str_c1) miss++;
    if (!C.str_d1) miss++;
    return miss ? -1 : 0;
}

static int lg_canary_probe(const char **step) {
    *step = "sym";
    if (!C.make_for_shader || !C.str_c1 || !C.str_d1) return 0;
    *step = "compile";
    char strbuf[128];
    memset(strbuf, 0, sizeof(strbuf));
    ((void (*)(void *, const char *))C.str_c1)(strbuf, kProbeSkSL);
    unsigned long opts[2] = {0, 0};
    void *sret[2] = {0, 0};
    const void *args[2] = {strbuf, opts};
    x8_call(sret, C.make_for_shader, args, 2);
    ((void (*)(void *))C.str_d1)(strbuf);
    if (!sret[0]) return 0;
    sk_unref(sret[0]);
    *step = "ok";
    return 1;
}
#endif

/* 引擎首次需要时调用：单飞；状态机放行 + 符号齐 + 金丝雀过 才武装；任一失败本会话永久拒绝 */
int lg_glass_arm_engine(void) {
    int expect = 0;
    if (!g_arm.compare_exchange_strong(expect, 2)) {
        return g_arm.load() == 1;
    }
    if (g_gate.load() != 1) {
        lg_log_throttled("deny-state", "%s ARMED=0 reason=state-locked", TAG);
        return 0;
    }
    if (g_guard_trip.load()) {
        lg_log_throttled("deny-trip", "%s ARMED=0 reason=guard-tripped", TAG);
        return 0;
    }
    if (lg_resolve_engine_syms() != 0) {
        lg_logf("%s ARMED=0 reason=sym-miss", TAG);
        return 0;
    }
    const char *step = "?";
    if (!lg_canary_probe(&step)) {
        lg_logf("%s CANARY fail step=%s engine-denied-for-session", TAG, step);
        return 0;
    }
    g_arm.store(1);
    lg_logf("%s ARMED ver=" LG_TRAMP_VER, TAG);
    return 1;
}

int lg_glass_engine_alive(void) {
    return g_arm.load() == 1 && !g_guard_trip.load();
}

/* 引擎每次失败调用；超阈永久熔断 */
void lg_guard_note_error(void) {
    int n = g_guard_err.fetch_add(1) + 1;
    if (n >= LG_ERR_TRIP && !g_guard_trip.exchange(1)) {
        lg_logf("%s TRIP reason=err-threshold n=%d engine-off-for-session", TAG, n);
    } else {
        lg_log_throttled("gerr", "%s engine-error n=%d", TAG, n);
    }
}

/* ---------- 装载层 ---------- */
static int is_surfaceflinger(void) {
    char buf[128];
    int fd = open("/proc/self/cmdline", O_RDONLY);
    if (fd < 0) return 0;
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) return 0;
    buf[n] = 0;
    const char *base = strrchr(buf, '/');
    return strcmp(base ? base + 1 : buf, "surfaceflinger") == 0;
}

static int lg_on_load(void) {
    g_load_calls++;
    lg_logf("%s ALIVE ver=" LG_TRAMP_VER " pid=%d", TAG, (int)getpid());
    void *real = dlopen(REAL_PATH, RTLD_NOLOAD | RTLD_LAZY);
    lg_logf("%s REAL handle=%p", TAG, real);
    return lg_state_run();
}

__attribute__((constructor)) static void lg_ctor(void) {
    if (is_surfaceflinger()) (void)lg_on_load();
}
