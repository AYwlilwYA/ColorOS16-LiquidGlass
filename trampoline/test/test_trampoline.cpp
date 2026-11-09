// 单测：装载层转发准入 + 状态机判定表 + 引擎门禁金丝雀 + 护栏熔断 + 日志节流
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
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
static std::string g_path_holder; /* 保持 g_state_path 指向的串存活 */

static std::string path_of(const char *name) { return g_out + "/" + name; }

static void set_state_path(const std::string &p) {
    g_path_holder = p;
    g_state_path = g_path_holder.c_str();
}

static void write_file(const std::string &p, const char *content) {
    FILE *f = fopen(p.c_str(), "w");
    if (!f) return;
    fputs(content, f);
    fclose(f);
}

static std::string read_file(const std::string &p) {
    FILE *f = fopen(p.c_str(), "r");
    if (!f) return "";
    char buf[256] = {0};
    size_t n = fread(buf, 1, sizeof(buf) - 1, f);
    fclose(f);
    (void)n;
    return buf;
}

static void reset_all(void) {
    g_gate.store(0);
    g_arm.store(0);
    g_guard_err.store(0);
    g_guard_trip.store(0);
    g_load_calls = 0;
    g_test_sym_ok = 1;
    memset(g_thr, 0, sizeof(g_thr));
    g_test_log[0] = 0;
    unsetenv("LG_TEST_CANARY");
}

static void set_boot(const char *bid) { g_test_boot_id = bid; }

static void test_ctor_non_sf_noop(void) {
    reset_all();
    std::string p = path_of("st_ctor");
    write_file(p, "healthy BID-OLD 1\n");
    set_state_path(p);
    set_boot("BID-CUR");
    lg_ctor();
    CHECK(g_load_calls == 0, "ctor-non-sf-no-load");
    CHECK(read_file(p) == "healthy BID-OLD 1\n", "ctor-non-sf-state-untouched");
}

static void test_fresh_grant(void) {
    reset_all();
    std::string p = path_of("st_fresh");
    unlink(p.c_str());
    set_state_path(p);
    set_boot("BID-1");
    int granted = lg_on_load();
    CHECK(granted == 1, "fresh-grant");
    CHECK(g_gate.load() == 1, "fresh-gate-open");
    CHECK(read_file(p) == "booting BID-1 1\n", "fresh-write-booting");
    CHECK(g_load_calls == 1, "fresh-load-count");
    CHECK(strstr(g_test_log, "ALIVE") != NULL, "fresh-alive-log");
    CHECK(strstr(g_test_log, "REAL handle=") != NULL, "fresh-real-log");
}

static void test_fresh_write_fail_locks(void) {
    reset_all();
    set_state_path(path_of("no_such_dir_xyz/state"));
    set_boot("BID-1");
    int granted = lg_on_load();
    CHECK(granted == 0, "fresh-write-fail-deny");
    CHECK(g_gate.load() == 0, "fresh-write-fail-gate-closed");
    CHECK(strstr(g_test_log, "state-write-fail") != NULL, "fresh-write-fail-reason");
}

static void test_disabled_locked(void) {
    reset_all();
    std::string p = path_of("st_disabled");
    write_file(p, "disabled BID-OLD 2\n");
    set_state_path(p);
    set_boot("BID-1");
    int granted = lg_on_load();
    CHECK(granted == 0, "disabled-deny");
    CHECK(read_file(p) == "disabled BID-OLD 2\n", "disabled-file-untouched");
    CHECK(strstr(g_test_log, "locked-disabled") != NULL, "disabled-reason");
}

static void test_prev_booting_unclean(void) {
    reset_all();
    std::string p = path_of("st_unclean");
    write_file(p, "booting BID-OLD 1\n");
    set_state_path(p);
    set_boot("BID-NEW");
    int granted = lg_on_load();
    CHECK(granted == 0, "prev-unclean-deny");
    CHECK(read_file(p).rfind("disabled ", 0) == 0, "prev-unclean-writes-disabled");
    CHECK(strstr(g_test_log, "prev-boot-unclean") != NULL, "prev-unclean-reason");
}

static void test_unconfirmed_restart_locks(void) {
    reset_all();
    std::string p = path_of("st_unconf");
    write_file(p, "booting BID-1 1\n");
    set_state_path(p);
    set_boot("BID-1");
    int granted = lg_on_load();
    CHECK(granted == 0, "unconfirmed-restart-deny");
    CHECK(read_file(p).rfind("disabled ", 0) == 0, "unconfirmed-writes-disabled");
    CHECK(strstr(g_test_log, "unconfirmed-restart") != NULL, "unconfirmed-reason");
}

static void test_boot_rearm_from_service(void) {
    reset_all();
    std::string p = path_of("st_rearm");
    write_file(p, "booting BID-1 0\n");
    set_state_path(p);
    set_boot("BID-1");
    int granted = lg_on_load();
    CHECK(granted == 1, "boot-rearm-grant");
    CHECK(read_file(p) == "booting BID-1 1\n", "boot-rearm-increments");
}

static void test_prev_healthy_new_boot(void) {
    reset_all();
    std::string p = path_of("st_prevok");
    write_file(p, "healthy BID-OLD 1\n");
    set_state_path(p);
    set_boot("BID-NEW");
    int granted = lg_on_load();
    CHECK(granted == 1, "prev-healthy-grant");
    CHECK(read_file(p) == "booting BID-NEW 1\n", "prev-healthy-arms-new-boot");
    CHECK(strstr(g_test_log, "prev-boot-healthy") != NULL, "prev-healthy-reason");
}

static void test_same_boot_restart(void) {
    reset_all();
    std::string p = path_of("st_samere");
    write_file(p, "healthy BID-1 1\n");
    set_state_path(p);
    set_boot("BID-1");
    int granted = lg_on_load();
    CHECK(granted == 1, "same-boot-restart-grant");
    CHECK(read_file(p) == "healthy BID-1 2\n", "same-boot-keeps-healthy");
}

static void test_restart_budget(void) {
    reset_all();
    std::string p = path_of("st_budget");
    write_file(p, "healthy BID-1 3\n");
    set_state_path(p);
    set_boot("BID-1");
    int granted = lg_on_load();
    CHECK(granted == 0, "budget-deny");
    CHECK(read_file(p).rfind("disabled ", 0) == 0, "budget-writes-disabled");
    CHECK(strstr(g_test_log, "restart-budget") != NULL, "budget-reason");
}

static void test_state_corrupt(void) {
    reset_all();
    std::string p = path_of("st_corrupt");
    write_file(p, "weird x1\n");
    set_state_path(p);
    set_boot("BID-1");
    int granted = lg_on_load();
    CHECK(granted == 0, "corrupt-deny");
    CHECK(read_file(p).rfind("disabled ", 0) == 0, "corrupt-writes-disabled");
    CHECK(strstr(g_test_log, "state-corrupt") != NULL, "corrupt-reason");
}

static void test_state_unreadable(void) {
    reset_all();
    std::string p = g_out; /* 目录当状态文件：读失败但非 ENOENT */
    set_state_path(p);
    set_boot("BID-1");
    int granted = lg_on_load();
    CHECK(granted == 0, "unreadable-deny");
    CHECK(strstr(g_test_log, "state-unreadable") != NULL, "unreadable-reason");
}

static void test_arm_gate_pass(void) {
    reset_all();
    g_gate.store(1);
    CHECK(lg_glass_arm_engine() == 1, "arm-pass");
    CHECK(lg_glass_arm_engine() == 1, "arm-idempotent");
    CHECK(lg_glass_engine_alive() == 1, "alive-after-arm");
    CHECK(strstr(g_test_log, "ARMED ver=") != NULL, "arm-log");
}

static void test_arm_state_locked(void) {
    reset_all();
    g_gate.store(0);
    CHECK(lg_glass_arm_engine() == 0, "arm-locked-deny");
    CHECK(lg_glass_arm_engine() == 0, "arm-locked-stays-denied");
    CHECK(strstr(g_test_log, "state-locked") != NULL, "arm-locked-reason");
}

static void test_arm_canary_fail_permanent(void) {
    reset_all();
    g_gate.store(1);
    setenv("LG_TEST_CANARY", "fail", 1);
    CHECK(lg_glass_arm_engine() == 0, "canary-fail-deny");
    unsetenv("LG_TEST_CANARY");
    CHECK(lg_glass_arm_engine() == 0, "canary-fail-permanent");
    CHECK(strstr(g_test_log, "CANARY fail") != NULL, "canary-fail-log");
}

static void test_arm_sym_miss_permanent(void) {
    reset_all();
    g_gate.store(1);
    g_test_sym_ok = 0;
    CHECK(lg_glass_arm_engine() == 0, "sym-miss-deny");
    g_test_sym_ok = 1;
    CHECK(lg_glass_arm_engine() == 0, "sym-miss-permanent");
    CHECK(strstr(g_test_log, "sym-miss") != NULL, "sym-miss-log");
}

static void test_guard_trip(void) {
    reset_all();
    g_gate.store(1);
    CHECK(lg_glass_arm_engine() == 1, "guard-arm-first");
    for (int i = 0; i < LG_ERR_TRIP; i++) lg_guard_note_error();
    CHECK(g_guard_trip.load() == 1, "guard-trip-set");
    CHECK(lg_glass_engine_alive() == 0, "guard-alive-off");
    CHECK(strstr(g_test_log, "TRIP reason=err-threshold") != NULL, "guard-trip-log");
    lg_guard_note_error();
    CHECK(strstr(g_test_log, "engine-error n=") != NULL, "guard-error-throttled-log");
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
    test_fresh_grant();
    test_fresh_write_fail_locks();
    test_disabled_locked();
    test_prev_booting_unclean();
    test_unconfirmed_restart_locks();
    test_boot_rearm_from_service();
    test_prev_healthy_new_boot();
    test_same_boot_restart();
    test_restart_budget();
    test_state_corrupt();
    test_state_unreadable();
    test_arm_gate_pass();
    test_arm_state_locked();
    test_arm_canary_fail_permanent();
    test_arm_sym_miss_permanent();
    test_guard_trip();
    test_log_throttle();
    test_forwarding();

    printf("RESULT: %s pass=%d fail=%d\n", g_fail ? "FAIL" : "PASS", g_pass, g_fail);
    return g_fail ? 1 : 0;
}
