// Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codewhisperer

import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.github.tomakehurst.wiremock.junit.WireMockRule
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.DisposableRule
import com.intellij.testFramework.replaceService
import org.gradle.internal.impldep.com.amazonaws.ResponseMetadata
import org.junit.Before
import org.junit.Rule
import org.mockito.kotlin.any
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.spy
import org.mockito.kotlin.whenever
import software.amazon.awssdk.awscore.DefaultAwsResponseMetadata
import software.amazon.awssdk.services.codewhisperer.CodeWhispererClient
import software.amazon.awssdk.services.codewhisperer.model.CodeScanStatus
import software.amazon.awssdk.services.codewhisperer.model.CreateCodeScanRequest
import software.amazon.awssdk.services.codewhisperer.model.CreateCodeScanResponse
import software.amazon.awssdk.services.codewhisperer.model.CreateUploadUrlRequest
import software.amazon.awssdk.services.codewhisperer.model.CreateUploadUrlResponse
import software.amazon.awssdk.services.codewhisperer.model.GetCodeScanRequest
import software.amazon.awssdk.services.codewhisperer.model.GetCodeScanResponse
import software.amazon.awssdk.services.codewhisperer.model.ListCodeScanFindingsRequest
import software.amazon.awssdk.services.codewhisperer.model.ListCodeScanFindingsResponse
import software.aws.toolkits.jetbrains.core.MockClientManagerRule
import software.aws.toolkits.jetbrains.services.codewhisperer.codescan.CodeWhispererCodeScanIssue
import software.aws.toolkits.jetbrains.services.codewhisperer.codescan.CodeWhispererCodeScanManager
import software.aws.toolkits.jetbrains.services.codewhisperer.codescan.sessionconfig.CodeScanSessionConfig
import software.aws.toolkits.jetbrains.services.codewhisperer.codescan.sessionconfig.PythonCodeScanSessionConfig
import software.aws.toolkits.jetbrains.services.codewhisperer.credentials.CodeWhispererClientManager
import software.aws.toolkits.jetbrains.services.codewhisperer.util.CodeWhispererConstants
import software.aws.toolkits.jetbrains.utils.rules.HeavyJavaCodeInsightTestFixtureRule
import software.aws.toolkits.jetbrains.utils.rules.PythonCodeInsightTestFixtureRule

open class CodeWhispererCodeScanTestBase {
    @Rule
    @JvmField
    val applicationRule = ApplicationRule()

    @Rule
    @JvmField
    val pythonProjectRule = PythonCodeInsightTestFixtureRule()

    @Rule
    @JvmField
    val javaProjectRule = HeavyJavaCodeInsightTestFixtureRule()

    @Rule
    @JvmField
    val disposableRule = DisposableRule()

    @Rule
    @JvmField
    val mockClientManagerRule = MockClientManagerRule()

    @Rule
    @JvmField
    val wireMock = WireMockRule(WireMockConfiguration.wireMockConfig().dynamicPort())

    internal lateinit var mockClient: CodeWhispererClient
    internal lateinit var s3endpoint: String

    internal lateinit var fakeGetUploadUrlResponse: CreateUploadUrlResponse
    internal lateinit var fakeCreateCodeScanResponse: CreateCodeScanResponse
    internal lateinit var fakeCreateCodeScanResponseFailed: CreateCodeScanResponse
    internal lateinit var fakeCreateCodeScanResponsePending: CreateCodeScanResponse
    internal lateinit var fakeListCodeScanFindingsResponse: ListCodeScanFindingsResponse
    internal lateinit var fakeGetCodeScanResponse: GetCodeScanResponse
    internal lateinit var fakeGetCodeScanResponsePending: GetCodeScanResponse
    internal lateinit var fakeGetCodeScanResponseFailed: GetCodeScanResponse

    internal val metadata: DefaultAwsResponseMetadata = DefaultAwsResponseMetadata.create(
        mapOf(ResponseMetadata.AWS_REQUEST_ID to CodeWhispererTestUtil.testRequestId)
    )

    internal lateinit var scanManagerSpy: CodeWhispererCodeScanManager
    internal lateinit var project: Project

    @Before
    open fun setup() {
        scanManagerSpy = spy(CodeWhispererCodeScanManager.getInstance(project))
        doNothing().`when`(scanManagerSpy).addCodeScanUI(any())
        mockClient = mockClientManagerRule.create()
        s3endpoint = "http://127.0.0.1:${wireMock.port()}"
        setupClient()
        setupResponse()

        whenever(mockClient.createUploadUrl(any<CreateUploadUrlRequest>())).thenReturn(fakeGetUploadUrlResponse)
        whenever(mockClient.createCodeScan(any<CreateCodeScanRequest>())).thenReturn(fakeCreateCodeScanResponse)
        whenever(mockClient.getCodeScan(any<GetCodeScanRequest>())).thenReturn(fakeGetCodeScanResponse)
        whenever(mockClient.listCodeScanFindings(any<ListCodeScanFindingsRequest>())).thenReturn(fakeListCodeScanFindingsResponse)
    }

    open fun setupCodeScanFindings() = defaultCodeScanFindings()

    protected fun defaultCodeScanFindings(file: VirtualFile? = null) = """
        [
            {
                "filePath": "${file?.path}",
                "startLine": 1,
                "endLine": 2,
                "title": "test",
                "description": {
                    "text": "global variable",
                    "markdown": "### global variable"
                }                    
            },
            {
                "filePath": "${file?.path}",
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

    private fun setupClient() {
        val clientManager = spy(CodeWhispererClientManager.getInstance())
        doNothing().`when`(clientManager).dispose()
        whenever(clientManager.getClient()).thenReturn(mockClient)
        ApplicationManager.getApplication().replaceService(CodeWhispererClientManager::class.java, clientManager, disposableRule.disposable)
    }

    private fun setupResponse() {
        fakeGetUploadUrlResponse = CreateUploadUrlResponse.builder()
            .uploadId(UPLOAD_ID)
            .uploadUrl(s3endpoint)
            .responseMetadata(metadata)
            .build() as CreateUploadUrlResponse

        fakeCreateCodeScanResponse = CreateCodeScanResponse.builder()
            .status(CodeScanStatus.COMPLETED)
            .jobId(JOB_ID)
            .responseMetadata(metadata)
            .build() as CreateCodeScanResponse

        fakeCreateCodeScanResponseFailed = CreateCodeScanResponse.builder()
            .status(CodeScanStatus.FAILED)
            .jobId(JOB_ID)
            .responseMetadata(metadata)
            .build() as CreateCodeScanResponse

        fakeCreateCodeScanResponsePending = CreateCodeScanResponse.builder()
            .status(CodeScanStatus.PENDING)
            .jobId(JOB_ID)
            .responseMetadata(metadata)
            .build() as CreateCodeScanResponse

        fakeListCodeScanFindingsResponse = ListCodeScanFindingsResponse.builder()
            .codeScanFindings(setupCodeScanFindings())
            .responseMetadata(metadata)
            .build() as ListCodeScanFindingsResponse

        fakeGetCodeScanResponse = GetCodeScanResponse.builder()
            .status(CodeScanStatus.COMPLETED)
            .responseMetadata(metadata)
            .build() as GetCodeScanResponse

        fakeGetCodeScanResponsePending = GetCodeScanResponse.builder()
            .status(CodeScanStatus.PENDING)
            .responseMetadata(metadata)
            .build() as GetCodeScanResponse

        fakeGetCodeScanResponseFailed = GetCodeScanResponse.builder()
            .status(CodeScanStatus.FAILED)
            .responseMetadata(metadata)
            .build() as GetCodeScanResponse
    }
    companion object {
        const val getCodeScan_timeout = CodeWhispererConstants.PYTHON_CODE_SCAN_TIMEOUT_IN_SECONDS * CodeWhispererConstants.TOTAL_MILLIS_IN_SECOND
        const val UPLOAD_ID = "uploadId"
        const val JOB_ID = "jobId"
    }
}
