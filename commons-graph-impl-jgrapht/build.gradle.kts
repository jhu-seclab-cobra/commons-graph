dependencies {
    implementation(project(":jhu-seclab-cobra-commons-graph"))
    implementation(libs.cobra.commons.value)
    implementation(libs.jgrapht.core)
    implementation(libs.jgrapht.io)
    implementation(libs.kotlin.logging)
    implementation(libs.slf4j.api)
    testImplementation(libs.logback.classic)
}
