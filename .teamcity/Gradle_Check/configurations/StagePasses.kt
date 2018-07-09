package configurations

import jetbrains.buildServer.configs.kotlin.v2018_1.AbsoluteId
import jetbrains.buildServer.configs.kotlin.v2018_1.BuildStep
import jetbrains.buildServer.configs.kotlin.v2018_1.FailureAction
import jetbrains.buildServer.configs.kotlin.v2018_1.buildSteps.script
import jetbrains.buildServer.configs.kotlin.v2018_1.triggers.ScheduleTrigger
import jetbrains.buildServer.configs.kotlin.v2018_1.triggers.VcsTrigger
import jetbrains.buildServer.configs.kotlin.v2018_1.triggers.schedule
import jetbrains.buildServer.configs.kotlin.v2018_1.triggers.vcs
import model.CIBuildModel
import model.Stage
import model.TestType
import model.Trigger

class StagePasses(model: CIBuildModel, stage: Stage, prevStage: Stage?, containsDeferredTests: Boolean, rootProjectUuid: String) : BaseGradleBuildType(model, init = {
    uuid = "${model.projectPrefix}Stage_${stage.id}_Trigger"
    id = AbsoluteId(uuid)
    name = stage.name + " (Trigger)"

    applyDefaultSettings(this)
    artifactRules = "build/build-receipt.properties"

    val triggerExcludes = """
        -:design-docs
        -:subprojects/docs/src/docs/release
    """.trimIndent()
    val masterReleaseFiler = model.masterAndReleaseBranches.joinToString(prefix = "+:", separator = "\n+:")

    if (model.publishStatusToGitHub) {
        features {
            publishBuildStatusToGithub()
        }
    }

    if (stage.trigger == Trigger.eachCommit) {
        triggers.vcs {
            quietPeriodMode = VcsTrigger.QuietPeriodMode.USE_CUSTOM
            quietPeriod = 90
            triggerRules = triggerExcludes
            branchFilter = masterReleaseFiler
        }
    } else if (stage.trigger != Trigger.never) {
        triggers.schedule {
            if (stage.trigger == Trigger.weekly) {
                schedulingPolicy = weekly {
                    dayOfWeek = ScheduleTrigger.DAY.Saturday
                    hour = 1
                }
            } else {
                schedulingPolicy = daily {
                    hour = 0
                    minute = 30
                }
            }
            triggerBuild = always()
            withPendingChangesOnly = true
            param("revisionRule", "lastFinished")
            param("branchFilter", masterReleaseFiler)
        }

    }

    params {
        param("env.JAVA_HOME", "%linux.java8.oracle.64bit%")
    }

    steps {
        gradleWrapper {
            name = "GRADLE_RUNNER"
            tasks = "createBuildReceipt"
            gradleParams = "-PtimestampedVersion -Djava7Home=%linux.jdk.for.gradle.compile% -Djava9Home=%linux.java9.oracle.64bit% --daemon"
        }
        script {
            name = "CHECK_CLEAN_M2"
            executionMode = BuildStep.ExecutionMode.ALWAYS
            scriptContent = m2CleanScriptUnixLike
        }
        if (model.tagBuilds) {
            gradleWrapper {
                name = "TAG_BUILD"
                executionMode = BuildStep.ExecutionMode.ALWAYS
                tasks = "tagBuild"
                gradleParams = "-PteamCityUsername=%teamcity.username.restbot% -PteamCityPassword=%teamcity.password.restbot% -PteamCityBuildId=%teamcity.build.id% -PgithubToken=%github.ci.oauth.token% ${buildScanTag("StagePasses")}"
                buildFile = "gradle/buildTagging.gradle"
            }
        }
    }

    dependencies {
        if (!stage.runsIndependent && prevStage != null) {
            dependency(AbsoluteId("${model.projectPrefix}Stage_${prevStage.id}_Trigger")) {
                snapshot {
                    onDependencyFailure = FailureAction.ADD_PROBLEM
                }
            }
        }

        stage.specificBuilds.forEach {
            dependency(it.create(model, stage)) {
                snapshot {}
            }
        }

        stage.performanceTests.forEach { performanceTest ->
            dependency(AbsoluteId(performanceTest.asId(model))) {
                snapshot {}
            }
        }

        stage.functionalTests.forEach { testCoverage ->
            val isSplitIntoBuckets = testCoverage.testType != TestType.soak
            if (isSplitIntoBuckets) {
                model.subProjects.forEach { subProject ->
                    if (shouldBeSkipped(subProject, testCoverage)) {
                        return@forEach
                    }
                    if (subProject.containsSlowTests && stage.omitsSlowProjects) {
                        return@forEach
                    }
                    if (subProject.unitTests && testCoverage.testType.unitTests) {
                        dependency(AbsoluteId(testCoverage.asConfigurationId(model, subProject.name))) { snapshot {} }
                    } else if (subProject.functionalTests && testCoverage.testType.functionalTests) {
                        dependency(AbsoluteId(testCoverage.asConfigurationId(model, subProject.name))) { snapshot {} }
                    } else if (subProject.crossVersionTests && testCoverage.testType.crossVersionTests) {
                        dependency(AbsoluteId(testCoverage.asConfigurationId(model, subProject.name))) { snapshot {} }
                    }
                }
            } else {
                dependency(AbsoluteId(testCoverage.asConfigurationId(model))) {
                    snapshot {}
                }
            }
        }

        if (containsDeferredTests) {
            dependency(AbsoluteId("${rootProjectUuid}_deferred_tests")) { snapshot {} }
        }
    }
})
