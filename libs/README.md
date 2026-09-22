# libs/

这里放**编译期依赖**，它们不随本仓库分发（见根目录 `.gitignore` 的 `libs/*.jar`）。

自行从各自的官方渠道获取，按下面的文件名放进本目录：

```
libs/RTM-2.4.24-43.jar        RealTrainMod 2.4.24-43   by jp.ngt
libs/NGTLib-2.4.21-38.jar     NGTLib 2.4.21-38         by jp.ngt
```

文件名必须与 `build.gradle` 里的一致：

```gradle
dependencies {
    compileOnly files('libs/RTM-2.4.24-43.jar', 'libs/NGTLib-2.4.21-38.jar')
}
```

放好后 `gradlew build` 即可。两个 jar 只用于编译，不会打进产物。
