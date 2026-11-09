// 单测：装载层转发准入 + 引擎门禁金丝雀 + 护栏熔断 + 日志节流
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <string>

#include "../trampoline.cpp"

static int g_fail;
static int g_pass;

#define CHECK(cond, name)                                        \
    do {                                                         \
        if (cond) {                                              \
            printf("PASS %s\n", name);                           \
            g_pass++;                                            \
        } else {                                                 \
            printf("FAIL %s\n", name);                           \
            g_fail++;                                            \
        }                                                        \
    } while (0)

static std::string g_out;
static std::string g_tramp_so;
static std::string g_real_so;

static void reset_all(void) {
    g_arm.store(0);
    g_guard_err.store(0);
    g_guard_trip.store(0);
    g_test_sym_ok = 1;
    memset(g_thr, 0, sizeof(g_thr));
    g_test_log[0] = 0;
    unsetenv("LG_TEST_CANARY");
}

static void test_ctor_non_sf_noop(void) {
    reset_all();
    lg_ctor();
    CHECK(strstr(g_test_log, "ALIVE") == NULL, "ctor-non-sf-no-log");
}

static void test_on_load_logs(void) {
    reset_all();
    lg_on_load();
    CHECK(strstr(g_test_log, "ALIVE ver=") != NULL, "onload-alive-log");
    CHECK(strstr(g_test_log, "REAL handle=") != NULL, "onload-real-log");
}

static void test_arm_pass(void) {
    reset_all();
    CHECK(lg_glass_arm_engine() == 1, "arm-pass");
    CHECK(lg_glass_arm_engine() == 1, "arm-idempotent");
    CHECK(lg_glass_engine_alive() == 1, "alive-after-arm");
    CHECK(strstr(g_test_log, "ARMED ver=") != NULL, "arm-log");
}

static void test_arm_canary_fail_permanent(void) {
    reset_all();
    setenv("LG_TEST_CANARY", "fail", 1);
    CHECK(lg_glass_arm_engine() == 0, "canary-fail-deny");
    unsetenv("LG_TEST_CANARY");
    CHECK(lg_glass_arm_engine() == 0, "canary-fail-permanent");
    CHECK(strstr(g_test_log, "CANARY fail") != NULL, "canary-fail-log");
}

static void test_arm_sym_miss_permanent(void) {
    reset_all();
    g_test_sym_ok = 0;
    CHECK(lg_glass_arm_engine() == 0, "sym-miss-deny");
    g_test_sym_ok = 1;
    CHECK(lg_glass_arm_engine() == 0, "sym-miss-permanent");
    CHECK(strstr(g_test_log, "sym-miss") != NULL, "sym-miss-log");
}

static void test_guard_trip(void) {
    reset_all();
    CHECK(lg_glass_arm_engine() == 1, "guard-arm-first");
    for (int i = 0; i < LG_ERR_TRIP; i++) lg_guard_note_error();
    CHECK(g_guard_trip.load() == 1, "guard-trip-set");
    CHECK(lg_glass_engine_alive() == 0, "guard-alive-off");
    CHECK(strstr(g_test_log, "TRIP reason=err-threshold") != NULL, "guard-trip-log");
    lg_guard_note_error();
    CHECK(strstr(g_test_log, "engine-error n=") != NULL, "guard-error-throttled-log");
    CHECK(lg_glass_arm_engine() == 0, "arm-after-trip-denied");
}

static void test_log_throttle(void) {
    reset_all();
    for (int i = 0; i < 50; i++)
        lg_log_throttled("tkey", "%s ENGINE-ERR-MARK n=%d", TAG, i);
    int hits = 0;
    for (const char *p = strstr(g_test_log, "ENGINE-ERR-MARK"); p;
         p = strstr(p + 1, "ENGINE-ERR-MARK"))
        hits++;
    CHECK(hits == 1, "throttle-single-key-once");
    lg_log_throttled("tkey2", "%s ENGINE-ERR-MARK-2", TAG);
    CHECK(strstr(g_test_log, "ENGINE-ERR-MARK-2") != NULL, "throttle-other-key-logged");
}

static void test_forwarding(void) {
    if (g_tramp_so.empty() || g_real_so.empty()) {
        printf("FAIL forwarding-env\n");
        g_fail++;
        return;
    }
    void *h = dlopen(g_tramp_so.c_str(), RTLD_NOW);
    CHECK(h != NULL, "forward-trampoline-dlopen");
    if (!h) return;
    void *p = dlsym(h, "lg_real_probe");
    CHECK(p != NULL, "forward-dlsym-through-handle");
    CHECK(dlsym(h, "lg_on_load") == NULL, "forward-internal-hidden");
    void *r = dlopen(g_real_so.c_str(), RTLD_NOLOAD | RTLD_LAZY);
    CHECK(r != NULL, "forward-real-loaded-by-needed");
    if (r && p) {
        void *q = dlsym(r, "lg_real_probe");
        CHECK(q == p, "forward-address-equal");
        int (*fn)(void) = (int (*)(void))p;
        CHECK(fn() == 42, "forward-call-result");
    }
    if (r) dlclose(r);
    dlclose(h);

    std::string bak = g_real_so + ".bak";
    rename(g_real_so.c_str(), bak.c_str());
    dlerror();
    void *h2 = dlopen(g_tramp_so.c_str(), RTLD_NOW);
    const char *err = dlerror();
    CHECK(h2 == NULL && err != NULL, "forward-missing-real-clean-fail");
    if (h2) dlclose(h2);
    rename(bak.c_str(), g_real_so.c_str());
}

int main(int argc, char **argv) {
    if (argc > 1 && strcmp(argv[1], "--selfcheck") == 0) {
        printf("CONTROL selfcheck: expecting deliberate failure\n");
        CHECK(1 == 0, "selfcheck-deliberate-fail");
        printf("RESULT: %s pass=%d fail=%d\n", g_fail ? "FAIL" : "PASS", g_pass, g_fail);
        return g_fail ? 1 : 0;
    }
    const char *out = getenv("LG_TEST_OUT");
    if (!out) {
        printf("FAIL env-LG_TEST_OUT\n");
        return 2;
    }
    g_out = out;
    const char *tso = getenv("LG_TEST_TRAMP_SO");
    const char *rso = getenv("LG_TEST_REAL_SO");
    if (tso) g_tramp_so = tso;
    if (rso) g_real_so = rso;

    printf("CONTROL env: out=%s tramp=%s real=%s\n", out, tso ? tso : "-", rso ? rso : "-");

    test_ctor_non_sf_noop();
    test_on_load_logs();
    test_arm_pass();
    test_arm_canary_fail_permanent();
    test_arm_sym_miss_permanent();
    test_guard_trip();
    test_log_throttle();
    test_forwarding();

    printf("RESULT: %s pass=%d fail=%d\n", g_fail ? "FAIL" : "PASS", g_pass, g_fail);
    return g_fail ? 1 : 0;
}
