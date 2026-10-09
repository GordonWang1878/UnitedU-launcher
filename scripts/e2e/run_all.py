import sys, os, time, importlib
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lib
# 原有六段旅程(约 25 分钟);2026-09-30 测试轮加的九段在后面(约 80 分钟,monkey 最慢放最后)。
# 只跑一部分:E2E_ONLY=j_home,j_pkg python3 run_all.py
ALL = ["j_home", "j_edit", "j_channels", "j_upload", "j_settings", "j_apps_inputs", "j_onb",
       "j_persist", "j_upload_edge", "j_pkg", "j_settings_values", "j_overlays", "j_recreate", "j_idle", "j_i18n", "j_monkey"]
ONLY = [x for x in os.environ.get("E2E_ONLY", "").split(",") if x]
for m in (ONLY or ALL):
    t = time.time()
    lib.set_lang("en")      # 上一段(三语测试)中途出错时别把别的语言带进下一段
    try:
        importlib.import_module(m).run()
    except Exception as e:
        lib.check(f"{m} 脚本异常", False, repr(e))
        try: lib.home_intent()
        except Exception: pass
    print(f"--- {m} {time.time() - t:.0f}s", flush=True)
lib.summary()
