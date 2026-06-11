import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val localProperties = Properties().apply {
    val propertiesFile = rootProject.file("local.properties")
    if (propertiesFile.isFile) {
        propertiesFile.inputStream().use(::load)
    }
}

fun String.toBuildConfigString(): String {
    return "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

fun localProperty(name: String, defaultValue: String): String {
    return (localProperties.getProperty(name) ?: defaultValue).trim()
}

val opticalFlowServerBaseUrl = (
    localProperties.getProperty("opticalFlowServerBaseUrl")
        ?: providers.gradleProperty("opticalFlowServerBaseUrl").orNull
        ?: "https://optical-flow.example.com"
    ).trim().trimEnd('/')

val mqttDeviceId = localProperty("mqttDeviceId", "")
val mqttTopicPrefix = "gnss/$mqttDeviceId"
val mqttUsername = localProperty("mqttUsername", "device:$mqttDeviceId")
val mqttPassword = localProperty("mqttPassword", "")
val mqttHost = localProperty("mqttHost", "")
val mqttPort = localProperty("mqttPort", "1883").toInt()
val mqttProtocol = localProperty("mqttProtocol", "mqtt")
val mqttTopicCoordinates = localProperty("mqttTopicCoordinates", "$mqttTopicPrefix/coordinates")
val mqttTopicStatus = localProperty("mqttTopicStatus", "$mqttTopicPrefix/status")
val mqttTopicAlert = localProperty("mqttTopicAlert", "$mqttTopicPrefix/alert")
val mqttTopicImage = localProperty("mqttTopicImage", "$mqttTopicPrefix/image")
val mqttTopicVideo = localProperty("mqttTopicVideo", "$mqttTopicPrefix/video")
val mqttTopicStreamStatus = localProperty("mqttTopicStreamStatus", "$mqttTopicPrefix/stream/status")
val mqttTopicCommands = localProperty("mqttTopicCommands", "$mqttTopicPrefix/command/#")
val gnssApiBaseUrl = localProperty(
    "gnssApiBaseUrl",
    if (mqttHost.isNotBlank()) "https://$mqttHost" else ""
)
val gnssDeviceId = localProperty("gnssDeviceId", mqttDeviceId)

android {
    namespace = "com.example.gnssandopticalflowapp"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.gnssandopticalflowapp"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField(
            "String",
            "OPTICAL_FLOW_SERVER_BASE_URL",
            opticalFlowServerBaseUrl.toBuildConfigString()
        )
        buildConfigField("String", "GNSS_API_BASE_URL", gnssApiBaseUrl.toBuildConfigString())
        buildConfigField("String", "GNSS_DEVICE_ID", gnssDeviceId.toBuildConfigString())
        buildConfigField("String", "MQTT_DEVICE_ID", mqttDeviceId.toBuildConfigString())
        buildConfigField("String", "MQTT_USERNAME", mqttUsername.toBuildConfigString())
        buildConfigField("String", "MQTT_PASSWORD", mqttPassword.toBuildConfigString())
        buildConfigField("String", "MQTT_HOST", mqttHost.toBuildConfigString())
        buildConfigField("int", "MQTT_PORT", mqttPort.toString())
        buildConfigField("String", "MQTT_PROTOCOL", mqttProtocol.toBuildConfigString())
        buildConfigField(
            "String",
            "MQTT_TOPIC_COORDINATES",
            mqttTopicCoordinates.toBuildConfigString()
        )
        buildConfigField("String", "MQTT_TOPIC_STATUS", mqttTopicStatus.toBuildConfigString())
        buildConfigField("String", "MQTT_TOPIC_ALERT", mqttTopicAlert.toBuildConfigString())
        buildConfigField("String", "MQTT_TOPIC_IMAGE", mqttTopicImage.toBuildConfigString())
        buildConfigField("String", "MQTT_TOPIC_VIDEO", mqttTopicVideo.toBuildConfigString())
        buildConfigField(
            "String",
            "MQTT_TOPIC_STREAM_STATUS",
            mqttTopicStreamStatus.toBuildConfigString()
        )
        buildConfigField("String", "MQTT_TOPIC_COMMANDS", mqttTopicCommands.toBuildConfigString())
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    viewBinding {
        enable = true
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    implementation("org.opencv:opencv:5.0.0")
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.7")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.7")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("com.github.bumptech.glide:glide:4.16.0")
    annotationProcessor("com.github.bumptech.glide:compiler:4.16.0")
    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("com.squareup.retrofit2:converter-gson:3.0.0")

    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("com.google.ar:core:1.54.0")

    // OSM Map
    implementation("org.osmdroid:osmdroid-android:6.1.18")
    implementation("org.orekit:orekit:12.2.1")

    // Google Play Services Location for one-click GPS activation
    implementation("com.google.android.gms:play-services-location:21.3.0")

    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")


}
