# TvHome 的保留规则。
#
# 这个应用里**没有任何反射**:JSON 用 org.json 手写、没有 Class.forName、没有
# getIdentifier、没有序列化框架。所以 R8 看得见全部引用,不需要额外 keep。
# MainActivity 由系统按清单里的名字实例化,AGP 会自动为清单组件生成 keep 规则;
# 下面这条只是把这件事写明,免得日后有人以为漏了。
-keep class com.uniteduone.launcher.MainActivity { *; }
