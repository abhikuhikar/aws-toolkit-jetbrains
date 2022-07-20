// Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codewhisperer


import com.github.tomakehurst.wiremock.client.WireMock
import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.PsiFile
import com.intellij.testFramework.replaceService
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.apache.commons.codec.digest.DigestUtils
import org.assertj.core.api.Assertions
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.mockito.internal.verification.Times
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import software.amazon.awssdk.services.codewhisperer.model.CreateUploadUrlRequest
import software.aws.toolkits.core.utils.WaiterTimeoutException
import software.aws.toolkits.jetbrains.services.codewhisperer.codescan.CodeScanSessionContext
import software.aws.toolkits.jetbrains.services.codewhisperer.codescan.CodeWhispererCodeScanException
import software.aws.toolkits.jetbrains.services.codewhisperer.codescan.CodeWhispererCodeScanIssue
import software.aws.toolkits.jetbrains.services.codewhisperer.codescan.CodeWhispererCodeScanSession
import software.aws.toolkits.jetbrains.services.codewhisperer.codescan.Description
import software.aws.toolkits.jetbrains.services.codewhisperer.codescan.sessionconfig.CodeScanSessionConfig
import software.aws.toolkits.jetbrains.services.codewhisperer.codescan.sessionconfig.Payload
import software.aws.toolkits.jetbrains.services.codewhisperer.codescan.sessionconfig.PayloadContext
import software.aws.toolkits.jetbrains.services.codewhisperer.codescan.sessionconfig.PythonCodeScanSessionConfig
import software.aws.toolkits.jetbrains.services.codewhisperer.credentials.CodeWhispererClientManager
import software.aws.toolkits.jetbrains.services.codewhisperer.util.CodeWhispererConstants.PYTHON_CODE_SCAN_TIMEOUT_IN_SECONDS
import software.aws.toolkits.jetbrains.services.codewhisperer.util.CodeWhispererConstants.TOTAL_MILLIS_IN_SECOND
import software.aws.toolkits.telemetry.CodewhispererLanguage
import java.io.File
import java.io.FileInputStream
import java.util.Base64
import kotlin.test.assertNotNull

class CodeWhispererCodeScanTest : CodeWhispererCodeScanTestBase() {
    internal lateinit var psifile: PsiFile
    private lateinit var fakeSecurityIssues: List<CodeWhispererCodeScanIssue>

    @Before
    override fun setup() {
        project = pythonProjectRule.project
        psifile = pythonProjectRule.fixture.addFileToProject(
            "/test1.py",
            """import numpy as np
               import from module1 import helper
               
               def add(
            """.trimMargin()
        )
        fakeSecurityIssues = listOf(
            CodeWhispererCodeScanIssue(
                project,
                psifile.virtualFile,
                1,
                1,
                2,
                2,
                "test",
                Description("", "")
            )
        )
        super.setup()
    }

    @Test
    fun `test createUploadUrl()`() {
        val sessionContextMock = mock<CodeScanSessionContext>()
        val session = CodeWhispererCodeScanSession(sessionContextMock)

        val response = session.createUploadUrl("md5", "type")

        argumentCaptor<CreateUploadUrlRequest>().apply {
            verify(mockClient, Times(1)).createUploadUrl(capture())
            assertThat(response.uploadUrl()).isEqualTo(s3endpoint)
            assertThat(response.uploadId()).isEqualTo(UPLOAD_ID)
            assertThat(firstValue.contentMd5()).isEqualTo("md5")
            assertThat(firstValue.artifactTypeAsString()).isEqualTo("type")
        }
    }

    @Test
    fun `test uploadArtifactTOS3()`() {
        val sessionContextMock = mock<CodeScanSessionContext>()
        val session = CodeWhispererCodeScanSession(sessionContextMock)
        val file = file()
        wireMock.stubFor(
            WireMock.put("/")
                .willReturn(
                    WireMock.aResponse().withStatus(200)
                )
        )

        session.uploadArtifactTOS3(s3endpoint, file, "md5")

        WireMock.verify(
            WireMock.exactly(1),
            WireMock.putRequestedFor(WireMock.urlEqualTo("/"))
                .withHeader("Content-Type", WireMock.matching("application/zip"))
                .withHeader(CodeWhispererCodeScanSession.CONTENT_MD5, WireMock.matching("md5"))
                .withHeader(CodeWhispererCodeScanSession.SERVER_SIDE_ENCRYPTION, WireMock.matching(CodeWhispererCodeScanSession.AES256))
        )
    }

    @Test
    fun `test createUploadUrlAndUpload()`() {
        val file = file()
        val fileMd5: String = Base64.getEncoder().encodeToString(DigestUtils.md5(FileInputStream(file)))
        val sessionContextMock = mock<CodeScanSessionContext>()
        val sessionSpy = spy(CodeWhispererCodeScanSession(sessionContextMock))
        sessionSpy.stub {
            onGeneric { sessionSpy.createUploadUrl(any(), any()) }
                .thenReturn(fakeGetUploadUrlResponse)
        }
        doNothing().`when`(sessionSpy).uploadArtifactTOS3(any(), any(), any())

        sessionSpy.createUploadUrlAndUpload(file, "artifactType")

        val inOrder = inOrder(sessionSpy)
        inOrder.verify(sessionSpy).createUploadUrl(eq(fileMd5), eq("artifactType"))
        inOrder.verify(sessionSpy).uploadArtifactTOS3(eq(fakeGetUploadUrlResponse.uploadUrl()), eq(file), eq(fileMd5))
    }

    @Test
    fun `test mapToCodeScanIssues`() {
        val recommendations = listOf(
            """
                [
                    {
                        "filePath": "${file().path}",
                        "startLine": 1,
                        "endLine": 2,
                        "title": "test",
                        "description": {
                            "text": "global variable",
                            "markdown": "### global variable"
                        }                    
                    },
                    {
                        "filePath": "${file().path}",
                        "startLine": 1,
                        "endLine": 2,
                        "title": "test",
                        "description": {
                            "text": "global variable",
                            "markdown": "### global variable"
                        }                    
                    }
                ]
            """,
            """
                [
                    {
                        "filePath": "non-exist.py",
                        "startLine": 1,
                        "endLine": 2,
                        "title": "test",
                        "description": {
                            "text": "global variable",
                            "markdown": "### global variable"
                        }                    
                    }
                ]                
            """
        )

        val sessionContextMock = mock<CodeScanSessionContext>()
        whenever(sessionContextMock.project).thenReturn(project)
        val session = CodeWhispererCodeScanSession(sessionContextMock)

        val res = session.mapToCodeScanIssues(recommendations)
        assertThat(res).hasSize(2)
    }

    @Test
    fun `test run() - happypath`() {
        val sessionConfigSpy = spy(CodeScanSessionConfig.create(psifile.virtualFile, project) as? PythonCodeScanSessionConfig)
        assertNotNull(sessionConfigSpy)
        val payloadContext = PayloadContext(CodewhispererLanguage.Python, 1L, 1L, 1, 600)
        val codeScanContext = CodeScanSessionContext(project, sessionConfigSpy)
        val sessionMock = spy(CodeWhispererCodeScanSession(codeScanContext))

        sessionConfigSpy.stub {
            onGeneric { sessionConfigSpy.createPayload() }.thenReturn(Payload(payloadContext, file()))
        }
        sessionMock.stub {
            onGeneric { createUploadUrlAndUpload(any(), any()) }.thenReturn(fakeGetUploadUrlResponse)
            onGeneric { createCodeScan(any()) }.thenReturn(fakeCreateCodeScanResponse)
            onGeneric { listCodeScanFindings(any()) }.thenReturn(fakeListCodeScanFindingsResponse)
            onGeneric { getCodeScan(any()) }.thenReturn(fakeGetCodeScanResponse)
            onGeneric { mapToCodeScanIssues(any()) }.thenReturn(fakeSecurityIssues)
        }

        runBlocking {
            val response = sessionMock.run()
            assertThat(response.issues).hasSize(fakeSecurityIssues.size)
        }

        val inOrder = inOrder(sessionMock)
        inOrder.verify(sessionMock, Times(1)).createUploadUrlAndUpload(eq(file()), eq("SourceCode"))
        inOrder.verify(sessionMock, Times(1)).createCodeScan(eq(CodewhispererLanguage.Python.toString()))
        inOrder.verify(sessionMock, Times(1)).getCodeScan(any())
        inOrder.verify(sessionMock, Times(1)).listCodeScanFindings(eq("jobId"))
    }

    @Test(expected = CodeWhispererCodeScanException::class)
    fun `test run() - createCodeScan failed`() {
        val sessionConfigSpy = spy(CodeScanSessionConfig.create(psifile.virtualFile, project) as? PythonCodeScanSessionConfig)
        assertNotNull(sessionConfigSpy)
        val payloadContext = PayloadContext(CodewhispererLanguage.Python, 1L, 1L, 1, 600)
        val codeScanContext = CodeScanSessionContext(project, sessionConfigSpy)
        val sessionMock = spy(CodeWhispererCodeScanSession(codeScanContext))

        sessionConfigSpy.stub {
            onGeneric { sessionConfigSpy.createPayload() }.thenReturn(Payload(payloadContext, file()))
        }
        sessionMock.stub {
            onGeneric { createUploadUrlAndUpload(any(), any()) }.thenReturn(fakeGetUploadUrlResponse)
            onGeneric { createCodeScan(any()) }.thenReturn(fakeCreateCodeScanResponseFailed)
            onGeneric { listCodeScanFindings(any()) }.thenReturn(fakeListCodeScanFindingsResponse)
            onGeneric { getCodeScan(any()) }.thenReturn(fakeGetCodeScanResponse)
            onGeneric { mapToCodeScanIssues(any()) }.thenReturn(emptyList())
        }

        runBlocking {
            sessionMock.run()
        }
    }

    @Test(expected = WaiterTimeoutException::class)
    fun `test run() - getCodeScan pending timeout`() {
        val sessionConfigSpy = spy(CodeScanSessionConfig.create(psifile.virtualFile, project) as? PythonCodeScanSessionConfig)
        assertNotNull(sessionConfigSpy)
        val payloadContext = PayloadContext(CodewhispererLanguage.Python, 1L, 1L, 1, getCodeScan_timeout)
        val codeScanContext = CodeScanSessionContext(project, sessionConfigSpy)
        val sessionMock = spy(CodeWhispererCodeScanSession(codeScanContext))

        sessionConfigSpy.stub {
            onGeneric { sessionConfigSpy.createPayload() }.thenReturn(Payload(payloadContext, file()))
        }
        sessionMock.stub {
            onGeneric { createUploadUrlAndUpload(any(), any()) }.thenReturn(fakeGetUploadUrlResponse)
            onGeneric { createCodeScan(any()) }.thenReturn(fakeCreateCodeScanResponsePending)
            onGeneric { listCodeScanFindings(any()) }.thenReturn(fakeListCodeScanFindingsResponse)
            onGeneric { getCodeScan(any()) }.thenAnswer {
                runBlocking {
                    delay(getCodeScan_timeout + 1L)
                }
                fakeGetCodeScanResponsePending
            }
            onGeneric { mapToCodeScanIssues(any()) }.thenReturn(emptyList())
        }

        runBlocking {
            sessionMock.run()
        }
    }

    @Test(expected = CodeWhispererCodeScanException::class)
    fun `test run() - getCodeScan failed`() {
        val sessionConfigSpy = spy(CodeScanSessionConfig.create(psifile.virtualFile, project) as? PythonCodeScanSessionConfig)
        assertNotNull(sessionConfigSpy)
        val payloadContext = PayloadContext(CodewhispererLanguage.Python, 1L, 1L, 1, getCodeScan_timeout)
        val codeScanContext = CodeScanSessionContext(project, sessionConfigSpy)
        val sessionMock = spy(CodeWhispererCodeScanSession(codeScanContext))

        sessionConfigSpy.stub {
            onGeneric { sessionConfigSpy.createPayload() }.thenReturn(Payload(payloadContext, file()))
        }
        sessionMock.stub {
            onGeneric { createUploadUrlAndUpload(any(), any()) }.thenReturn(fakeGetUploadUrlResponse)
            onGeneric { createCodeScan(any()) }.thenReturn(fakeCreateCodeScanResponsePending)
            onGeneric { listCodeScanFindings(any()) }.thenReturn(fakeListCodeScanFindingsResponse)
            onGeneric { getCodeScan(any()) }.thenReturn(fakeGetCodeScanResponseFailed)
            onGeneric { mapToCodeScanIssues(any()) }.thenReturn(emptyList())
        }

        runBlocking {
            sessionMock.run()
        }
    }

/*
    @Test
    fun testSetup() {
        val sessionConfigSpy = spy(CodeScanSessionConfig.create(psifile.virtualFile, project) as? PythonCodeScanSessionConfig)
        assertNotNull(sessionConfigSpy)
        val payloadContext = PayloadContext(CodewhispererLanguage.Python, 1L, 1L, 1, 600)
        val codeScanContext = CodeScanSessionContext(project, sessionConfigSpy)
        val sessionMock = spy(CodeWhispererCodeScanSession(codeScanContext))

        sessionConfigSpy.stub {
            onGeneric { sessionConfigSpy.createPayload() }.thenReturn(Payload(payloadContext, file()))
        }
        sessionMock.stub {
            onGeneric { createUploadUrlAndUpload(any(), any()) }.thenReturn(fakeGetUploadUrlResponse)
            onGeneric { createCodeScan(any()) }.thenReturn(fakeCreateCodeScanResponse)
            onGeneric { listCodeScanFindings(any()) }.thenReturn(fakeListCodeScanFindingsResponse)
            onGeneric { getCodeScan(any()) }.thenReturn(fakeGetCodeScanResponse)
            onGeneric { mapToCodeScanIssues(any()) }.thenReturn(fakeSecurutiesIssues)
        }
        doNothing().`when`(sessionMock).sleepThread()

        ToolWindowManager.getInstance(pythonProjectRule.project).registerToolWindow(
            RegisterToolWindowTask(
                id = ProblemsView.ID
            )
        )

        runBlocking {
            println("Running code scan...")
            assert(!Disposer.isDisposed(pythonProjectRule.project))
            val job = scanManagerSpy.launchCodeScanCoroutine(sessionMock)
            job.join()
            assertNotNull(scanManagerSpy.getScanTree().model)
            val treeModel = scanManagerSpy.getScanTree().model as? CodeWhispererCodeScanTreeModel
            assertNotNull(treeModel)
            assertEquals(1, treeModel.getTotalIssuesCount())
        }
    }
*/

    private fun file() = File(psifile.virtualFile.path)

    private fun setupClient() {
        val clientManager = spy(CodeWhispererClientManager.getInstance())
        doNothing().`when`(clientManager).dispose()
        whenever(clientManager.getClient()).thenReturn(mockClient)
        ApplicationManager.getApplication().replaceService(CodeWhispererClientManager::class.java, clientManager, disposableRule.disposable)
    }

    companion object {
        const val getCodeScan_timeout = PYTHON_CODE_SCAN_TIMEOUT_IN_SECONDS * TOTAL_MILLIS_IN_SECOND
    }
}
