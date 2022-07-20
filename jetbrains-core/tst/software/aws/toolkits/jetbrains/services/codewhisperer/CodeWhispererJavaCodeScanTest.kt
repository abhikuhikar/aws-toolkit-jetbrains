// Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codewhisperer

import com.intellij.analysis.problemsView.toolWindow.ProblemsView
import com.intellij.compiler.CompilerTestUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.compiler.CompileContext
import com.intellij.openapi.compiler.CompilerManager
import com.intellij.openapi.compiler.CompilerMessageCategory
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.impl.SdkConfigurationUtil
import com.intellij.openapi.roots.CompilerProjectExtension
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.openapi.wm.RegisterToolWindowTask
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.IdeaTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.runInEdtAndWait
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.spy
import org.mockito.kotlin.stub
import software.aws.toolkits.jetbrains.services.codewhisperer.codescan.CodeScanSessionContext
import software.aws.toolkits.jetbrains.services.codewhisperer.codescan.CodeWhispererCodeScanException
import software.aws.toolkits.jetbrains.services.codewhisperer.codescan.CodeWhispererCodeScanManager
import software.aws.toolkits.jetbrains.services.codewhisperer.codescan.CodeWhispererCodeScanSession
import software.aws.toolkits.jetbrains.services.codewhisperer.codescan.CodeWhispererCodeScanTreeModel
import software.aws.toolkits.jetbrains.services.codewhisperer.codescan.sessionconfig.CodeScanSessionConfig
import software.aws.toolkits.jetbrains.services.codewhisperer.codescan.sessionconfig.JavaCodeScanSessionConfig
import software.aws.toolkits.jetbrains.services.codewhisperer.codescan.sessionconfig.Payload
import software.aws.toolkits.jetbrains.services.codewhisperer.codescan.sessionconfig.PayloadContext
import software.aws.toolkits.jetbrains.services.codewhisperer.codescan.sessionconfig.PythonCodeScanSessionConfig
import software.aws.toolkits.jetbrains.utils.rules.addClass
import software.aws.toolkits.jetbrains.utils.rules.addModule
import software.aws.toolkits.telemetry.CodewhispererLanguage
import java.io.BufferedInputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class CodeWhispererJavaCodeScanTest : CodeWhispererCodeScanTestBase() {

    internal lateinit var utilsJava: VirtualFile
    internal lateinit var test1Java: VirtualFile
    internal lateinit var test2Java: VirtualFile
    internal var sessionConfigSpy: JavaCodeScanSessionConfig? = null

    private var totalSize: Long = 0

    @Before
    override fun setup() {
        project = javaProjectRule.project
        setupJavaProject()
        sessionConfigSpy = spy(CodeScanSessionConfig.create(utilsJava, project) as? JavaCodeScanSessionConfig)
        super.setup()
    }

    override fun setupCodeScanFindings(): String {
        return defaultCodeScanFindings(utilsJava)
    }

    @Test
    fun `test createPayload`() {
        val payload = sessionConfigSpy?.createPayload()
        assertNotNull(payload)
        assertEquals(3, payload.context.totalFiles)
        assertEquals(totalSize, payload.context.payloadSize)
        assertEquals(CodewhispererLanguage.Java, payload.context.language)
        assertEquals(62, payload.context.totalLines)
        assertNotNull(payload.srcZip)

        val bufferedInputStream = BufferedInputStream(payload.srcZip.inputStream())
        val zis = ZipInputStream(bufferedInputStream)
        var filesInZip = 0
        while (zis.nextEntry != null) {
            filesInZip += 1
        }
        assertEquals(3, filesInZip)
        filesInZip = 0
        assertNotNull(payload.buildZip)
        val buildZis = ZipInputStream(BufferedInputStream(payload.buildZip!!.inputStream()))
        while (buildZis.nextEntry != null) {
            filesInZip += 1
        }
        assertEquals(3, filesInZip)
    }


    @Test
    fun `test getSourceFilesUnderProjectRoot`() {
        assertThat(sessionConfigSpy?.getSourceFilesUnderProjectRoot(utilsJava)?.size).isEqualTo(3)
    }

    @Test
    fun `test parseImports()`() {
        val utilsJavaImports = sessionConfigSpy?.parseImports(utilsJava)
        assertThat(utilsJavaImports?.imports?.size).isEqualTo(4)

        val test1JavaImports = sessionConfigSpy?.parseImports(test1Java)
        assertThat(test1JavaImports?.imports?.size).isEqualTo(1)
    }

    @Test
    fun `selected file larger than payload limit throws exception`() {
        sessionConfigSpy?.stub {
            onGeneric { getPayloadLimitInBytes() }.thenReturn(100)
        }
        assertThrows<CodeWhispererCodeScanException> {
            val payload = sessionConfigSpy?.createPayload()
        }
    }

    @Test
    fun `test createPayload with custom payload limit`() {
        sessionConfigSpy?.stub {
            onGeneric { getPayloadLimitInBytes() }.thenReturn(900)
        }
        val payload = sessionConfigSpy?.createPayload()
        assertNotNull(payload)
        assertEquals(1, payload.context.totalFiles)
        assertEquals(346, payload.context.payloadSize)
        assertEquals(CodewhispererLanguage.Java, payload.context.language)
        assertEquals(16, payload.context.totalLines)
        assertNotNull(payload.srcZip)

        val bufferedInputStream = BufferedInputStream(payload.srcZip.inputStream())
        val zis = ZipInputStream(bufferedInputStream)
        var filesInZip = 0
        while (zis.nextEntry != null) {
            filesInZip += 1
        }
        assertEquals(1, filesInZip)

        filesInZip = 0
        assertNotNull(payload.buildZip)
        val buildZis = ZipInputStream(BufferedInputStream(payload.buildZip!!.inputStream()))
        while (buildZis.nextEntry != null) {
            filesInZip += 1
        }
        assertEquals(1, filesInZip)
    }

    @Test
    fun `e2e happy path integration test`() {
        val payload = sessionConfigSpy?.createPayload()
        assertNotNull(payload)
        val codeScanContext = CodeScanSessionContext(project, sessionConfigSpy!!)
        val sessionMock = spy(CodeWhispererCodeScanSession(codeScanContext))

        doNothing().`when`(sessionMock).uploadArtifactTOS3(any(), any(), any())
        doNothing().`when`(sessionMock).sleepThread()

        runBlocking {
            println("Running code scan...")
            assert(!Disposer.isDisposed(javaProjectRule.project))
            val codeScanResponse = sessionMock.run()
            assertThat(codeScanResponse.issues).hasSize(2)
        }
    }

    private fun compileProject() {
        setUpCompiler()
        val compileFuture = CompletableFuture<CompileContext>()
        ApplicationManager.getApplication().invokeAndWait {
            CompilerManager.getInstance(javaProjectRule.project).rebuild { aborted, errors, _, context ->
                if (!aborted && errors == 0) {
                    compileFuture.complete(context)
                } else {
                    compileFuture.completeExceptionally(
                        RuntimeException(
                            "Compilation error: ${context.getMessages(CompilerMessageCategory.ERROR).map { it.message }}"
                        )
                    )
                }
            }
        }
        compileFuture.get(30, TimeUnit.SECONDS)
    }

    private fun setUpCompiler() {
        val project = javaProjectRule.project
        val modules = ModuleManager.getInstance(project).modules

        WriteCommandAction.writeCommandAction(project).run<Nothing> {
            val compilerExtension = CompilerProjectExtension.getInstance(project)!!
            compilerExtension.compilerOutputUrl = javaProjectRule.fixture.tempDirFixture.findOrCreateDir("out").url
            val jdkHome = IdeaTestUtil.requireRealJdkHome()
            VfsRootAccess.allowRootAccess(javaProjectRule.fixture.testRootDisposable, jdkHome)
            val jdkHomeDir = LocalFileSystem.getInstance().refreshAndFindFileByPath(jdkHome)!!
            val jdkName = "Real JDK"
            val jdk = SdkConfigurationUtil.setupSdk(emptyArray(), jdkHomeDir, JavaSdk.getInstance(), false, null, jdkName)!!

            ProjectJdkTable.getInstance().addJdk(jdk, javaProjectRule.fixture.testRootDisposable)

            for (module in modules) {
                ModuleRootModificationUtil.setModuleSdk(module, jdk)
            }
        }

        runInEdtAndWait {
            PlatformTestUtil.saveProject(project)
            CompilerTestUtil.saveApplicationSettings()
        }
    }

    private fun setupJavaProject() {
        val module = javaProjectRule.fixture.addModule("main")

        val utilsClass = javaProjectRule.fixture.addClass(module, """
            package com.example;

            import java.io.BufferedInputStream;
            import java.util.concurrent.CompletableFuture;
            import java.util.concurrent.TimeUnit;
            import java.util.zip.ZipInputStream;

            public class Utils {
                public int add(int a, int b) {
                    return a + b;
                }
                
                public int sub(int a, int b) {
                    return a - b;
                }
            }            
        """.trimIndent())
        utilsJava = utilsClass.containingFile.virtualFile
        totalSize += utilsJava.length

        val test1Class = javaProjectRule.fixture.addClass(module, """
            package com.example2;
            
            import com.example.Utils;
            
            public class Test1 {
                Utils utils = new Utils();
                
                public int fib(int n) {
                   if (n == 0 || n == 1) {
                      return n; 
                   }
                  return utils.add(fib(utils.sub(n,1)), fib(utils.sub(n,2)));
                }
                
                /**
                * Bubble sort algorithm to sort integer array.
                */
                public void bubbleSort(int[] arr) {  
                    int n = arr.length;  
                    int temp = 0;  
                    for(int i=0; i < n; i++) {
                         for(int j=1; j < (n-i); j++) {
                             if(arr[j-1] > arr[j]) {
                                 //swap elements
                                 temp = arr[j-1];
                                 arr[j-1] = arr[j];  
                                 arr[j] = temp;
                             }
                         }
                    }
                }  
            }
            
        """.trimIndent())
        test1Java = test1Class.containingFile.virtualFile
        totalSize += test1Java.length

        val test2Class = javaProjectRule.fixture.addClass(module, """
            package com.example2;
            
            import com.example.*;
            
            public class Test2 {
                private Utils utils = new Utils();
                
                int fib(int n) {
                   if (n == 0 || n == 1) {
                      return n; 
                   }
                  return utils.add(fib(utils.sub(n,1)), fib(utils.sub(n,2)));
                }
            }
        """.trimIndent())
        test2Java = test2Class.containingFile.virtualFile
        totalSize += test2Java.length

        val notIncludedMd = javaProjectRule.fixture.addFileToProject("/notIncluded.md", "### should NOT be included")

        compileProject()
    }
}
