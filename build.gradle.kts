plugins {
    base
}

tasks.named("check") {
    dependsOn(":apps:api:check")
}
