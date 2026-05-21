plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

android {
    namespace = "com.surveyanalytica.clickstream"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

group = "com.surveyanalytica"
version = "1.0.0"

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])

                groupId = "com.surveyanalytica"
                artifactId = "clickstream-android"
                version = "1.0.0"

                pom {
                    name.set("SurveyAnalytica Clickstream Android SDK")
                    description.set(
                        "Lightweight Android SDK for sending behavioral events to the " +
                            "SurveyAnalytica workflow engine."
                    )
                    url.set("https://github.com/aphougat/clickstream-android")
                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                        }
                    }
                    developers {
                        developer {
                            id.set("surveyanalytica")
                            name.set("SurveyAnalytica")
                            email.set("dev@surveyanalytica.com")
                        }
                    }
                    scm {
                        connection.set("scm:git:git://github.com/aphougat/clickstream-android.git")
                        developerConnection.set(
                            "scm:git:ssh://github.com/aphougat/clickstream-android.git"
                        )
                        url.set("https://github.com/aphougat/clickstream-android")
                    }
                }
            }
        }

        repositories {
            // GitHub Packages
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/aphougat/clickstream-android")
                credentials {
                    username = System.getenv("GITHUB_ACTOR") ?: ""
                    password = System.getenv("GITHUB_TOKEN") ?: ""
                }
            }
            // Local Maven for testing
            maven {
                name = "LocalMaven"
                url = uri("${rootProject.buildDir}/repo")
            }
        }
    }
}
