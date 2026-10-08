plugins {
    kotlin("jvm")
    application
}

kotlin { jvmToolchain(17) }

dependencies { implementation(project(":engine")) }

application { mainClass.set("com.alisport.goldpin.cli.MainKt") }

tasks.named<JavaExec>("run") {
    jvmArgs = listOf("-Xmx900m")
}
