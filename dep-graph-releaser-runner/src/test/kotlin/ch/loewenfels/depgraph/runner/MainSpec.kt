package ch.loewenfels.depgraph.runner

import ch.loewenfels.depgraph.console.ErrorHandler
import ch.loewenfels.depgraph.maven.getTestDirectory
import ch.loewenfels.depgraph.runner.Json.MAVEN_PARENT_ANALYSIS_OFF
import ch.loewenfels.depgraph.runner.Main.errorHandler
import ch.loewenfels.depgraph.runner.Main.fileVerifier
import ch.loewenfels.depgraph.serialization.Serializer
import ch.tutteli.atrium.*
import ch.tutteli.atrium.api.cc.en_UK.isTrue
import ch.tutteli.atrium.api.cc.en_UK.returnValueOf
import ch.tutteli.spek.extensions.TempFolder
import org.jetbrains.spek.api.Spek
import org.jetbrains.spek.api.dsl.*
import java.io.File
import java.util.*


object MainSpec : Spek({
    val tempFolder = TempFolder.perAction()
    registerListener(tempFolder)
    errorHandler = object : ErrorHandler {
        override fun error(msg: String) = throw AssertionError(msg)
    }
    fileVerifier = object: Main.FileVerifier {
        override fun file(path: String, fileDescription: String) = File(path)
    }

    describe("json") {
        given("project A with dependent project B (happy case)") {
            on("calling main") {
                val jsonFile = File(tempFolder.tmpDir, "test.json")
                Main.main(
                    "json", "com.example", "a",
                    getTestDirectory("managingVersions/inDependency").absolutePath,
                    jsonFile.absolutePath
                )
                it("creates a corresponding json file") {
                    assert(jsonFile).returnValueOf(jsonFile::exists).isTrue()
                }

                test("the json file can be de-serialized and is expected project A with dependent B") {
                    val json = Scanner(jsonFile, Charsets.UTF_8.name()).useDelimiter("\\Z").use { it.next() }
                    val releasePlan = Serializer().deserialize(json)
                    assertProjectAWithDependentB(releasePlan)
                    assertReleasePlanHasNoWarningsAndNoInfos(releasePlan)
                }
            }
        }

        given("parent not in analysis, does not matter with $MAVEN_PARENT_ANALYSIS_OFF") {
            on("calling main") {
                val jsonFile = File(tempFolder.tmpDir, "test.json")
                Main.main(
                    "json", "com.example", "b",
                    getTestDirectory("errorCases/parentNotInAnalysis").absolutePath,
                    jsonFile.absolutePath,
                    MAVEN_PARENT_ANALYSIS_OFF
                )
                it("creates a corresponding json file") {
                    assert(jsonFile).returnValueOf(jsonFile::exists).isTrue()
                }

                test("the json file can be de-serialized and is expected project A with dependent B") {
                    val json = Scanner(jsonFile, Charsets.UTF_8.name()).useDelimiter("\\Z").use { it.next() }
                    val releasePlan = Serializer().deserialize(json)
                    assertSingleProject(releasePlan, exampleB)
                }
            }
        }
    }

    describe("update") {
        given("single project with third party dependency") {
            val pom = File(getTestDirectory("singleProject"), "pom.xml")

            context("dependency shall be updated, same version") {
                on("calling main") {
                    val tmpPom = copyPom(tempFolder, pom)

                    testSameContent(tempFolder, pom) {
                        Main.main("update", tmpPom.absolutePath, "junit", "junit", "4.12")
                    }
                }
            }

            context("dependency shall be updated, new version") {
                on("calling main") {
                    val tmpPom = copyPom(tempFolder, pom)

                    it("updates the dependency") {
                        Main.main("update", tmpPom.absolutePath, "junit", "junit", "4.4")
                        assertSameAsBeforeAfterReplace(tmpPom, pom, "4.12", "4.4")
                    }
                }
            }
        }
    }
})
