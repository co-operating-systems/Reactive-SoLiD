/*
 * Copyright 2021 Henry Story
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package run.cosy.ldp

/** set of test web servers that can be called by test suites */
object WebServers:
   val importsDL: TestCompiler        = TestCompiler(ImportsDLTestServer)
   val connectedImports: TestCompiler = TestCompiler(ConnectedImportsDLTestServer)
   val aclBasic: TestCompiler         = TestCompiler(BasicACLTestServerW3C)
