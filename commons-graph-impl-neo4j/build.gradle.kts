dependencies {
    implementation(project(":jhu-seclab-cobra-commons-graph"))
    implementation(libs.neo4j.core)
    implementation(libs.cobra.commons.value)
    implementation(libs.kotlin.logging)
    implementation(libs.slf4j.api)
    testImplementation(libs.logback.classic)
}
