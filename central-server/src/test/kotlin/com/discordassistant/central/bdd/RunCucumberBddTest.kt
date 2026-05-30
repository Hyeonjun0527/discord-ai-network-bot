package com.discordassistant.central.bdd

import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

/**
 * Cucumber BDD 스위트(차수 18). Gradle 이 cucumber 엔진을 안정적으로 디스커버하도록 스위트로 묶는다.
 * Docker 게이트는 build.gradle.kts 에서 클래스 단위 제외로 처리(기본 빌드 제외, 실행: -PdockerTests).
 * 글루/리포터는 junit-platform.properties 의 cucumber.glue / cucumber.plugin.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
class RunCucumberBddTest
