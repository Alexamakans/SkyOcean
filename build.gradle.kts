@file:Suppress("UnstableApiUsage")

import com.google.devtools.ksp.gradle.KspAATask
import dev.detekt.gradle.Detekt
import dev.detekt.gradle.DetektCreateBaselineTask
import net.fabricmc.loom.task.ValidateAccessWidenerTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import kotlin.io.path.createDirectories

plugins {
    idea
    id("fabric-loom")
    kotlin("jvm") version "2.2.20"
    alias(libs.plugins.kotlin.symbol.processor)
    alias(libs.plugins.meowdding.resources)
    alias(libs.plugins.meowdding.auto.mixins)
    alias(libs.plugins.detekt)
    `versioned-catalogues`
    `museum-data`
}

repositories {
    fun scopedMaven(url: String, vararg paths: String) = maven(url) { content { paths.forEach(::includeGroupAndSubgroups) } }

    scopedMaven("https://pkgs.dev.azure.com/djtheredstoner/DevAuth/_packaging/public/maven/v1", "me.djtheredstoner")
    scopedMaven("https://repo.hypixel.net/repository/Hypixel", "net.hypixel")
    scopedMaven("https://maven.parchmentmc.org/", "org.parchmentmc")
    scopedMaven("https://api.modrinth.com/maven", "maven.modrinth")
    scopedMaven(
        "https://maven.teamresourceful.com/repository/maven-public/",
        "earth.terrarium",
        "com.teamresourceful",
        "tech.thatgravyboat",
        "me.owdding",
        "com.terraformersmc"
    )
    scopedMaven("https://maven.nucleoid.xyz/", "eu.pb4")
    mavenCentral()
}

configurations {
    modImplementation {
        attributes.attribute(Attribute.of("earth.terrarium.cloche.modLoader", String::class.java), "fabric")
    }
}

dependencies {
    attributesSchema {
        attribute(Attribute.of("earth.terrarium.cloche.minecraftVersion", String::class.java)) {
            disambiguationRules.add(ClocheDisambiguationRule::class) {
                params(versionedCatalog.versions.getOrFallback("sbapi-mc-version", "minecraft").toString())
            }
        }
    }

    minecraft(versionedCatalog["minecraft"])
    mappings(loom.layered {
        officialMojangMappings()
        parchment(variantOf(versionedCatalog["parchment"]) {
            artifactType("zip")
        })
    })

    includeImplementation(versionedCatalog["resourceful.config"])
    includeImplementation(versionedCatalog["resourceful.lib"])
    includeImplementation(versionedCatalog["placeholders"])
    includeImplementation(versionedCatalog["placeholders"])
    includeImplementation(libs.resourceful.config.kotlin)
    includeImplementation(versionedCatalog["olympus"])
    includeImplementation(libs.meowdding.remote.repo)
    includeImplementation(libs.meowdding.lib)
    includeImplementation(libs.skyblockapi)

    implementation(libs.keval)
    include(libs.keval)

    modImplementation(versionedCatalog["fabric.api"])
    modImplementation(libs.fabric.language.kotlin)
    compileOnly(libs.skyblockapi.repo)
    compileOnly(libs.fabric.loader)

    compileOnly(libs.meowdding.ktmodules)
    compileOnly(libs.meowdding.ktcodecs)

    ksp(libs.meowdding.ktmodules)
    ksp(libs.meowdding.ktcodecs)

    detektPlugins(libs.detekt.ktlintWrapper)
}

fun DependencyHandler.includeImplementation(dep: Any) {
    include(dep)
    modImplementation(dep)
}

val mcVersion = stonecutter.current.version.replace(".", "")
val accessWidenerFile = rootProject.file("src/skyocean.accesswidener")
loom {
    runConfigs["client"].apply {
        ideConfigGenerated(true)
        runDir = "../../run"
        vmArg("-Dfabric.modsFolder=" + '"' + "${mcVersion}Mods" + '"')
    }

    if (accessWidenerFile.exists()) {
        accessWidenerPath.set(accessWidenerFile)
    }
}

val datagenOutput = project.layout.buildDirectory.file("generated/skyocean/data").apply {
    get().asFile.toPath().createDirectories()
}
fabricApi {
    configureDataGeneration {
        client = true
        modId = "skyocean-datagen"
        createSourceSet = true
        createRunConfiguration = true
        outputDirectory.set(datagenOutput)
    }
}

ksp {
    arg("meowdding.package", "me.owdding.skyocean.generated")
}

afterEvaluate {
    loom {
        runs.named("datagen") {
            this.vmArgs.add("-Dskyocean.extraPaths=\"\"")
        }
    }

    tasks.withType(KspAATask::class.java).configureEach {
        kspConfig.processorOptions.put(
            "meowdding.project_name",
            "SkyOcean" + (kspConfig.cachesDir.get().asFile.name.takeUnless { it == "main" }?.replaceFirstChar { it.uppercaseChar() } ?: "")
        )
    }
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
    withSourcesJar()
}

val archiveName = "SkyOcean"

base {
    archivesName.set("$archiveName-${archivesName.get()}")
}

tasks.named("build") {
    doLast {
        val sourceFile = rootProject.projectDir.resolve("versions/${project.name}/build/libs/${archiveName}-${stonecutter.current.version}-$version.jar")
        val targetFile = rootProject.projectDir.resolve("build/libs/${archiveName}-$version-${stonecutter.current.version}.jar")
        targetFile.parentFile.mkdirs()
        targetFile.writeBytes(sourceFile.readBytes())
    }

    fun addDependencies(arg: ClocheDependencyHandler) = arg.apply {
        implementation(libs.meowdding.lib)
        implementation(libs.skyblockapi)
        compileOnly(libs.skyblockapi.repo)
        implementation(libs.keval)
        implementation(libs.placeholders)
        implementation(libs.resourceful.config.kotlin)

        implementation(libs.fabric.language.kotlin)

        implementation("org.mongodb:mongodb-driver-reactivestreams:5.5.1")
        implementation("org.mongodb:mongodb-driver-core:5.5.1")
        implementation("org.mongodb:bson:5.5.1")
    }

    common {
        project.layout.projectDirectory.dir("src/mixins").toPath().listDirectoryEntries().filter { it.isRegularFile() }.forEach {
            mixins.from("src/mixins/${it.name}")
        }
        accessWideners.from(project.layout.projectDirectory.file("src/skyocean.accesswidener"))

        data {
            dependencies { addDependencies(this) }
        }
        dependencies { addDependencies(this) }
    }

    fun createVersion(
        name: String,
        version: String = name,
        loaderVersion: Provider<String> = libs.versions.fabric.loader,
        fabricApiVersion: Provider<String> = libs.versions.fabric.api,
        endAtSameVersion: Boolean = true,
        minecraftVersionRange: ModMetadata.VersionRange.() -> Unit = {
            start = version
            if (endAtSameVersion) {
                end = version
                endExclusive = false
            }
        },
        dependencies: MutableMap<String, Provider<MinimalExternalModuleDependency>>.() -> Unit = { },
    ) {
        val dependencies = mutableMapOf<String, Provider<MinimalExternalModuleDependency>>().apply(dependencies)
        val olympus = dependencies["olympus"]!!
        val rlib = dependencies["resourcefullib"]!!
        val rconfig = dependencies["resourcefulconfig"]!!
        val accesswidener = project.layout.projectDirectory.file("src/versions/${name}/skyocean.accesswidener")

        fabric("versions:$name") {
            tasks.named<Jar>(lowerCamelCaseGradleName(this.sourceSet.name, "includeJar")) {
                archiveClassifier = name
            }
            includedClient()
            minecraftVersion = version

            this.loaderVersion = loaderVersion.get()

            mixins.from("src/mixins/versioned/skyocean.${name.replace(".", "")}.mixins.json")

            println("Acceswidener: " + accesswidener.toPath().toAbsolutePath().toString())
            if (accesswidener.toPath().exists()) {
                accessWideners.from(accesswidener)
            }

            // include(libs.hypixelapi) - included in sbapi

            metadata {
                fun kotlin(value: String): Action<FabricMetadata.Entrypoint> = Action {
                    adapter = "kotlin"
                    this.value = value
                }
                entrypoint("client", kotlin("me.owdding.skyocean.SkyOcean"))
                entrypoint("fabric-datagen", kotlin("me.owdding.skyocean.datagen.dispatcher.SkyOceanDatagenEntrypoint"))

                fun dependency(modId: String, version: Provider<String>? = null) {
                    dependency {
                        this.modId = modId
                        this.required = true
                        if (version != null) version {
                            this.start = version
                        }
                    }
                }

                dependency {
                    modId = "minecraft"
                    required = true
                    version(minecraftVersionRange)
                }
                dependency("fabric")
                dependency("fabricloader", loaderVersion)
                dependency("fabric-language-kotlin", libs.versions.fabric.language.kotlin)
                dependency("resourcefullib", rlib.map { it.version!! })
                dependency("skyblock-api", libs.versions.skyblockapi.asProvider())
                dependency("olympus", olympus.map { it.version!! })
                dependency("placeholder-api", libs.versions.placeholders)
                dependency("resourcefulconfigkt", libs.versions.rconfigkt)
                dependency("resourcefulconfig", rconfig.map { it.version!! })
                dependency("meowdding-lib", libs.versions.meowdding.lib)
            }

            data()

            dependencies {
                fabricApi(fabricApiVersion, minecraftVersion)
                implementation(olympus)
                implementation(rconfig)
                implementation(rlib)
                compileOnly(libs.meowdding.remote.repo)

                val mongoVersion = "5.5.1"
                val reactorVersion = "3.6.9"

                include("org.mongodb:bson:$mongoVersion") { isTransitive = false }
                include("org.mongodb:mongodb-driver-core:$mongoVersion") { isTransitive = false }
                include("org.mongodb:mongodb-driver-reactivestreams:$mongoVersion") { isTransitive = false }

                // >>> Add Reactor (runtime needed) <<<
                include("io.projectreactor:reactor-core:$reactorVersion") { isTransitive = false }

                // (optional) only if not already pulled by reactor-core
                include("org.reactivestreams:reactive-streams:1.0.4") { isTransitive = false }

                include(libs.resourceful.config.kotlin) { isTransitive = false }
                include(libs.keval) { isTransitive = false }
                include(libs.placeholders) { isTransitive = false }
                include(rlib) { isTransitive = false }
                include(olympus) { isTransitive = false }
                include(rconfig) { isTransitive = false }

                include(libs.skyblockapi) { isTransitive = false }
                include(libs.meowdding.lib) { isTransitive = false }

                val mods = project.layout.buildDirectory.get().toPath().resolve("tmp/extracted${sourceSet.name}RuntimeMods")
                val modsTmp = project.layout.buildDirectory.get().toPath().resolve("tmp/extracted${sourceSet.name}RuntimeMods/tmp")

                mods.deleteRecursively()
                modsTmp.createDirectories()
                mods.createDirectories()

                fun extractMods(file: java.nio.file.Path) {
                    println("Adding runtime mod ${file.name}")
                    val extracted = mods.resolve(file.name)
                    file.copyTo(extracted, overwrite = true)
                    if (!file.fileName.endsWith(".disabled.jar")) {
                        modRuntimeOnly(files(extracted))
                    }
                    ZipFile(extracted.toFile()).use {
                        it.entries().asIterator().forEach { file ->
                            val name = file.name.replace(File.separator, "/")
                            if (name.startsWith("META-INF/jars/") && name.endsWith(".jar")) {
                                val data = it.getInputStream(file).readAllBytes()
                                val file = modsTmp.resolve(name.substringAfterLast("/"))
                                file.writeBytes(data, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE)
                                extractMods(file)
                            }
                        }
                    }
                }

                project.layout.projectDirectory.toPath().resolve("run/${name}Mods").takeIf { it.exists() }
                    ?.listDirectoryEntries()?.filter { it.isRegularFile() }?.forEach { file ->
                        extractMods(file)
                    }

                modsTmp.deleteRecursively()
            }

            runs {
                clientData {
                    mainClass("net.fabricmc.loader.impl.launch.knot.KnotClient")
                }
                client()
            }

        }
    }

    createVersion("1.21.5", fabricApiVersion = provider { "0.127.1" }) {
        this["resourcefullib"] = libs.resourceful.lib1215
        this["resourcefulconfig"] = libs.resourceful.config1215
        this["olympus"] = libs.olympus.lib1215
    }
    createVersion("1.21.8", minecraftVersionRange = {
        start = "1.21.6"
        end = "1.21.8"
        endExclusive = false
    }) {
        this["resourcefullib"] = libs.resourceful.lib1218
        this["resourcefulconfig"] = libs.resourceful.config1218
        this["olympus"] = libs.olympus.lib1218
    }
    /*createVersion("1.21.9", endAtSameVersion = false, fabricApiVersion = provider { "0.134.0" }) {
        this["resourcefullib"] = libs.resourceful.lib1219
        this["resourcefulconfig"] = libs.resourceful.config1219
        this["olympus"] = libs.olympus.lib1219
    }*/

    mappings { official() }
}

compactingResources {
    basePath = "repo"
    pathDirectory = "../../src"

    configureTask(tasks.named<AbstractCopyTask>("processResources").get())

    removeComments("unobtainable_ids")
    downloadResource("https://raw.githubusercontent.com/NotEnoughUpdates/NotEnoughUpdates-REPO/refs/heads/master/constants/dyes.json", "dyes.json")
    downloadResource("https://raw.githubusercontent.com/NotEnoughUpdates/NotEnoughUpdates-REPO/refs/heads/master/constants/animatedskulls.json", "skulls.json")
    downloadResource(
        "https://raw.githubusercontent.com/Campionnn/SkyShards-Parser/55483450ff83e1bf1e453f31797cedb08b0c2733/shard-data.json",
        "skyshards_data.json"
    )
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
    compilerOptions.optIn.add("kotlin.time.ExperimentalTime")
    compilerOptions.freeCompilerArgs.addAll(
        "-Xcontext-parameters",
        "-Xcontext-sensitive-resolution"
    )
}

tasks.processResources {
    val replacements = mapOf(
        "version" to version,
        "minecraft_start" to versionedCatalog.versions.getOrFallback("minecraft.start", "minecraft"),
        "minecraft_end" to versionedCatalog.versions.getOrFallback("minecraft.end", "minecraft"),
        "fabric_lang_kotlin" to libs.versions.fabric.language.kotlin.get(),
        "sbapi" to libs.versions.skyblockapi.asProvider().get(),
        "rlib" to versionedCatalog.versions["resourceful.lib"],
        "olympus" to versionedCatalog.versions["olympus"],
        "mlib" to libs.versions.meowdding.lib.get(),
        "rconfigkt" to libs.versions.rconfigkt.get(),
        "rconfig" to versionedCatalog.versions["resourceful.config"],
        "placeholder_api" to versionedCatalog.versions["placeholders"]
    )
    inputs.properties(replacements)

    filesMatching("fabric.mod.json") {
        expand(replacements)
    }
}

autoMixins {
    mixinPackage = "me.owdding.skyocean.mixins"
    projectName = "skyocean"
}

tasks.withType<ProcessResources>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    filesMatching(listOf("**/*.fsh", "**/*.vsh")) {
        // `#` is used for all versions, `!` is used for multiversioned imports
        filter { if (it.startsWith("//#moj_import") || it.startsWith("//!moj_import")) "#${it.substring(3)}" else it }
    }
    with(copySpec {
        from(rootProject.file("src/lang")).include("*.json").into("assets/skyocean/lang")
    })
    with(copySpec {
        from(accessWidenerFile)
    })
}

idea {
    module {
        isDownloadJavadoc = true
        isDownloadSources = true

        excludeDirs.add(file("run"))
    }
}

tasks.withType<ValidateAccessWidenerTask> { enabled = false }

tasks.withType<Jar> {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

detekt {
    source.setFrom(project.sourceSets.map { it.allSource })
    config.from(files("$rootDir/detekt/detekt.yml"))
    baseline = file("$rootDir/detekt/${project.name}-baseline.xml")
    buildUponDefaultConfig = true
    parallel = true
}

tasks.named { it == "jar" || it == "sourcesJar" }.configureEach {
    if (this !is Jar) return@configureEach
    if (rootProject.hasProperty("datagen")) {
        dependsOn(tasks.named("runDatagen"))
        with(copySpec {
            from(datagenOutput).exclude(".cache/**")
        })
    }
}

tasks.withType<Detekt>().configureEach {
    onlyIf {
        !rootProject.hasProperty("skipDetekt")
    }
    exclude { it.file.toPath().toAbsolutePath().startsWith(project.layout.buildDirectory.get().asFile.toPath()) }
    reports {
        html.required.set(true)
        xml.required.set(true)
        sarif.required.set(true)
        md.required.set(true)
    }
}
tasks.withType<DetektCreateBaselineTask>().configureEach {
    exclude { it.file.toPath().toAbsolutePath().startsWith(project.layout.buildDirectory.get().asFile.toPath()) }
    outputs.upToDateWhen { false }
}
