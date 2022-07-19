// Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codewhisperer

import com.intellij.testFramework.runInEdtAndWait
import org.junit.Rule
import software.aws.toolkits.jetbrains.utils.rules.PythonCodeInsightTestFixtureRule

class CodeWhispererCodeScanTestUtil {
    //Setup Java and Python projects
    val pythonProjectRule = PythonCodeInsightTestFixtureRule()
    val testPy = pythonProjectRule.fixture.addFileToProject(
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

    val utilsPy = pythonProjectRule.fixture.addFileToProject(
        "/utils.py",
        """
            def add(num1, num2
                return num1 + num2
                
            def multiply(num1, num2)
                return num1 * num2
        """.trimIndent()
    ).virtualFile
    val helperPy = pythonProjectRule.fixture.addFileToProject(
        "/helpers/helper.py",
        """
            from helpers import helper as h
            def subtract(num1, num2)
                return num1 - num2
            
            def fib(num):
                if num in [0,1]: return num
                return h.add(fib(num-1), fib(num-2))                
        """.trimIndent()
    ).virtualFile

    val notIncludedMd = pythonProjectRule.fixture.addFileToProject("/notIncluded.md", "### should NOT be included")
}
