package io.github.mangi.eta.agent.terminal

/** Official release metadata verified on 2026-11-15. Unsupported libc combinations remain explicit. */
internal object LinuxSdkArtifacts {
    fun artifactFor(tool: ManagedLinuxTool, distribution: LinuxDistribution, abis: List<String>): VerifiedArtifact? =
        abis.firstNotNullOfOrNull { abi ->
            val arm = when(abi) { "arm64-v8a" -> true; "x86_64" -> false; else -> return@firstNotNullOfOrNull null }
            when(tool) {
                ManagedLinuxTool.MAVEN -> VerifiedArtifact("maven-all", "3.10.0", "apache-maven-3.10.0-bin.tar.gz",
                    "https://downloads.apache.org/maven/maven-3/3.10.0/binaries/apache-maven-3.10.0-bin.tar.gz",
                    "a46cc51bc74fa23fd267c7a0b9132b146dcf526da60d31aa5174e565632e9e0e", 9_979_885L,
                    fallbackUrls = listOf("https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.10.0/apache-maven-3.10.0-bin.tar.gz"))
                ManagedLinuxTool.GRADLE -> VerifiedArtifact("gradle-all", "9.9.1", "gradle-9.9.1-bin.zip",
                    "https://services.gradle.org/distributions/gradle-9.9.1-bin.zip",
                    "d8a18a7a841dd5df825d35a685fad6f2d7f12ac0c280e57b67c27b4a520ba228", 134_921_139L,
                    fallbackUrls = listOf("https://downloads.gradle.org/distributions/gradle-9.9.1-bin.zip"))
                ManagedLinuxTool.GO -> {
                    val arch = if(arm) "arm64" else "amd64"
                    val file = "go1.27.4.linux-$arch.tar.gz"
                    VerifiedArtifact("go-$arch", "1.27.4", file, "https://go.dev/dl/$file",
                        if(arm) "3392582988a273a87ab5043ee1b2c9c17a2c3c00580bf9326c47a2e0aaaeac92" else "7226736c2339719cda28fbf3a54d71aa63bcefe376e98cef00034268dab24840",
                        if(arm) 67_129_258L else 70_680_823L)
                }
                ManagedLinuxTool.RUST -> {
                    val libc = if(distribution == LinuxDistribution.ALPINE) "musl" else "gnu"
                    val arch = if(arm) "aarch64" else "x86_64"
                    val triple = "$arch-unknown-linux-$libc"
                    val digest = when(triple) {
                        "aarch64-unknown-linux-gnu" -> "a21f6a21818df07c1e2e7addb570af5444b3fa9dd61e3a207f4fb1c5a5e665c9"
                        "aarch64-unknown-linux-musl" -> "fb99a831b7ba7216181133eb80ce199f583fd8e9e3b43442648ab67860e74315"
                        "x86_64-unknown-linux-gnu" -> "98ee6bec240e1cfe944f17e7092412dc5ef6e008484bb668c6936d35a7d15acd"
                        else -> "fda6aa497a5c40df71274f92463b8cf0a1d36040dc9d22ef62236fc40e62b648"
                    }
                    val size = if(arm) { if(libc == "gnu") 189_083_072L else 282_332_904L } else { if(libc == "gnu") 211_225_068L else 327_831_468L }
                    val file = "rust-1.100.0-$triple.tar.xz"
                    VerifiedArtifact("rust-$triple", "1.100.0", file, "https://static.rust-lang.org/dist/2026-11-12/$file", digest, size)
                }
                ManagedLinuxTool.JDK -> {
                    // The published OpenJDK GA archives use glibc; Alpine must use its supported APK package.
                    if(distribution == LinuxDistribution.ALPINE) return@firstNotNullOfOrNull null
                    val arch = if(arm) "aarch64" else "x64"
                    val file = "openjdk-27.0.1.0.3_linux-${arch}_bin.tar.gz"
                    VerifiedArtifact("jdk-$arch", "27.0.1.0.3", file,
                        "https://download.java.net/java/GA/jdk27.0.1.0.3/b14170c9b2de463d9b89442c6db3e384/1/GPL/$file",
                        if(arm) "a088a727fa3e76ebeadfa76ed9c1e9fecbdfa54ec752ecb669c38a8585e058a1" else "256199399aaddc6d66919b5de90794b4263e4ab312aa56692cb63b70a0abef41",
                        if(arm) 224_370_069L else 226_491_134L)
                }
                else -> null
            }
        }
}
