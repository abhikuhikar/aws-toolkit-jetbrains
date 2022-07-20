// Copyright 2021 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.utils.compatability

import com.jetbrains.rider.model.debuggerWorker.DotNetCoreAttachStartInfo

fun createNetCoreAttachStartInfo(pid: Int) = DotNetCoreAttachStartInfo(
    processId = pid
)
