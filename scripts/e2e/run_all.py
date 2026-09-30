import sys, os, time, importlib
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lib
for m in ["j_home", "j_edit", "j_upload", "j_settings", "j_apps_inputs", "j_onb"]:
    t = time.time()
    try:
        importlib.import_module(m).run()
    except Exception as e:
        lib.check(f"{m} 脚本异常", False, repr(e))
        try: lib.home_intent()
        except Exception: pass
    print(f"--- {m} {time.time() - t:.0f}s", flush=True)
lib.summary()
