# LibXposed 官方推荐的模块 R8 规则（来自 api 工程 README）
# - 入口类需保留无参构造
# - META-INF/xposed/java_init.list 需保留（混淆重命名入口类时同步改写清单）
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
