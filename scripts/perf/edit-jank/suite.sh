# 用法:bash scripts/perf/edit-jank/suite.sh <tag>
# H 首页导航(对照)/ A 进编辑页 / B 上下换层 / C 层内左右 / D 拿起搬运 / E 退出 / A2 E2 第二次进出。按键剧本与诊断报告逐键相同。
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/u.sh"
source "$HERE/../../env.sh"
g=$1
adb -s $S shell am force-stop $P
adb -s $S shell am start -a android.intent.action.MAIN -c android.intent.category.HOME -n $P/.MainActivity >/dev/null
sleep 5
k 20; sleep 1
run H_$g 16 22 22 22 22 22 21 21 21 21 21 20 20 19 19 20 20 19 19
k 19 19 21 21 21; k 23; sleep 1.2; k 20 23; sleep 1.5; dumpui | head -1 > $T/pre_A_$g.txt
run A_$g 5 23; sleep 1; dumpui | head -1 > $T/post_A_$g.txt
run B_$g 14 20 20 20 20 20 20 19 19 19 19 19 19
run C_$g 15 22 22 22 22 22 22 22 21 21 21 21 21 21 21
run D_$g 12 23 22 22 22 20 19 21 21 21 4
sleep 1; dumpui | head -1 > $T/pre_E_$g.txt
run E_$g 5 4
sleep 1.5; run A2_$g 5 23; sleep 1; run E2_$g 5 4
k 4; sleep 1; k 4; sleep 1.5
