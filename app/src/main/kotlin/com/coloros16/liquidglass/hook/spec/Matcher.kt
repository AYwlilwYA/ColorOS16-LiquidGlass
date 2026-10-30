package com.coloros16.liquidglass.hook.spec

import java.lang.reflect.Method

/**
 * 按 MatchSpec 解析 hook 目标方法：类（host classLoader）+ 方法名 + 参数精确匹配。
 * declared=true 走 getDeclaredMethod，false 走 getMethod（含继承）；accessible=true 补 setAccessible。
 */
object Matcher {

    fun resolveMethod(classLoader: ClassLoader, match: MatchSpec): Method {
        val className = requireNotNull(match.className) { "match.className required (method=${match.methodName})" }
        val methodName = requireNotNull(match.methodName) { "match.methodName required (class=$className)" }
        val clazz = Class.forName(className, false, classLoader)
        val params = match.params.map { resolveType(classLoader, it) }.toTypedArray()
        val method = if (match.declared) {
            clazz.getDeclaredMethod(methodName, *params)
        } else {
            clazz.getMethod(methodName, *params)
        }
        if (match.accessible) method.isAccessible = true
        match.returnType?.let { rt ->
            val actual = method.returnType
            if (actual.name != rt && actual.canonicalName != rt) {
                throw NoSuchMethodException("$className#$methodName returnType $actual != $rt")
            }
        }
        return method
    }

    private fun resolveType(classLoader: ClassLoader, name: String): Class<*> = when (name) {
        "void" -> Void.TYPE
        "boolean" -> Boolean::class.javaPrimitiveType!!
        "byte" -> Byte::class.javaPrimitiveType!!
        "short" -> Short::class.javaPrimitiveType!!
        "int" -> Int::class.javaPrimitiveType!!
        "long" -> Long::class.javaPrimitiveType!!
        "float" -> Float::class.javaPrimitiveType!!
        "double" -> Double::class.javaPrimitiveType!!
        "char" -> Char::class.javaPrimitiveType!!
        else -> Class.forName(name, false, classLoader)
    }
}
