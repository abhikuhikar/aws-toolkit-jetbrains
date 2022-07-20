// Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codewhisperer


import com.intellij.openapi.vfs.VirtualFile
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.spy
import org.mockito.kotlin.stub
import software.aws.toolkits.jetbrains.services.codewhisperer.codescan.CodeWhispererCodeScanException
import software.aws.toolkits.jetbrains.services.codewhisperer.codescan.sessionconfig.CodeScanSessionConfig
import software.aws.toolkits.jetbrains.services.codewhisperer.codescan.sessionconfig.PythonCodeScanSessionConfig
import software.aws.toolkits.telemetry.CodewhispererLanguage
import java.io.BufferedInputStream
import java.util.zip.ZipInputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class CodeWhispererPythonCodeScanTest : CodeWhispererCodeScanTestBase() {

    internal lateinit var testPy: VirtualFile
    internal lateinit var utilsPy: VirtualFile
    internal lateinit var helperPy: VirtualFile
    internal var sessionConfig: PythonCodeScanSessionConfig? = null

    private var totalSize: Long = 0

    @Before
    override fun setup() {
        setupPythonProject()
        project = pythonProjectRule.project
        sessionConfig = spy(CodeScanSessionConfig.create(testPy, project) as? PythonCodeScanSessionConfig)
        super.setup()
    }

    @Test
    fun `test createPayload`() {
        val payload = sessionConfig?.createPayload()
        assertNotNull(payload)
        assertEquals(3, payload.context.totalFiles)
        assertEquals(totalSize, payload.context.payloadSize)
        assertEquals(CodewhispererLanguage.Python, payload.context.language)
        assertEquals(50, payload.context.totalLines)
        assertNotNull(payload.srcZip)

        val bufferedInputStream = BufferedInputStream(payload.srcZip.inputStream())
        val zis = ZipInputStream(bufferedInputStream)
        var filesInZip = 0
        while (zis.nextEntry != null) {
            filesInZip += 1
        }

        assertEquals(3, filesInZip)
    }


    @Test
    fun `test getSourceFilesUnderProjectRoot`() {
        assertThat(sessionConfig?.getSourceFilesUnderProjectRoot(testPy)?.size).isEqualTo(3)
    }

    @Test
    fun `test parseImport()`() {
        val testPyImports = sessionConfig?.parseImports(testPy)
        assertThat(testPyImports?.size).isEqualTo(4)

        val helperPyImports = sessionConfig?.parseImports(helperPy)
        assertThat(helperPyImports?.size).isEqualTo(1)
    }

    @Test
    fun `selected file larger than payload limit throws exception`() {
        sessionConfig?.stub {
            onGeneric { getPayloadLimitInBytes() }.thenReturn(100)
        }
        assertThrows<CodeWhispererCodeScanException> {
            val payload = sessionConfig?.createPayload()
        }
    }

    @Test
    fun `test createPayload with custom payload limit`() {
        sessionConfig?.stub {
            onGeneric { getPayloadLimitInBytes() }.thenReturn(900)
        }
        val payload = sessionConfig?.createPayload()
        assertNotNull(payload)
        assertEquals(1, payload.context.totalFiles)
        assertEquals(155, payload.context.payloadSize)
        assertEquals(CodewhispererLanguage.Python, payload.context.language)
        assertEquals(10, payload.context.totalLines)
        assertNotNull(payload.srcZip)

        val bufferedInputStream = BufferedInputStream(payload.srcZip.inputStream())
        val zis = ZipInputStream(bufferedInputStream)
        var filesInZip = 0
        while (zis.nextEntry != null) {
            filesInZip += 1
        }

        assertEquals(1, filesInZip)
    }

    private fun setupPythonProject() {
        testPy = pythonProjectRule.fixture.addFileToProject(
            "/test1.py",
            """
            import numpy as np
            import utils
            import helpers.helper
            import test2

            a = 1
            b = 2
            print(utils.add(a, b))
            println(helper.subtract(a, b))
            println(utils.fib(5))
        """.trimIndent()
        ).virtualFile
        totalSize += testPy.length

        utilsPy = pythonProjectRule.fixture.addFileToProject(
            "/utils.py",
            """
            def add(num1, num2
                return num1 + num2

            def multiply(num1, num2)
                return num1 * num2

            def divide(num1, num2)
                return num1 / num2

            def bubbleSort(arr):
            	n = len(arr)
            	# optimize code, so if the array is already sorted, it doesn't need
            	# to go through the entire process
            	swapped = False
            	# Traverse through all array elements
            	for i in range(n-1):
            		# range(n) also work but outer loop will
            		# repeat one time more than needed.
            		# Last i elements are already in place
            		for j in range(0, n-i-1):

            			# traverse the array from 0 to n-i-1
            			# Swap if the element found is greater
            			# than the next element
            			if arr[j] > arr[j + 1]:
            				swapped = True
            				arr[j], arr[j + 1] = arr[j + 1], arr[j]
            		
            		if not swapped:
            			# if we haven't needed to make a single swap, we
            			# can just exit the main loop.
            			return

        """.trimIndent()
        ).virtualFile
        totalSize += utilsPy.length

        helperPy = pythonProjectRule.fixture.addFileToProject(
            "/helpers/helper.py",
            """
            from helpers import helper as h
            def subtract(num1, num2)
                return num1 - num2
            
            def fib(num):
                if num == 0: return 0
                if num in [1,2]: return 1
                return h.add(fib(num-1), fib(num-2))                

        """.trimIndent()
        ).virtualFile
        totalSize += helperPy.length

        val notIncludedMd = pythonProjectRule.fixture.addFileToProject("/notIncluded.md", "### should NOT be included")
    }
}
