/*
 * Copyright 2021 Henry Story
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package run.cosy.http.auth

import org.w3.banana.{FOAFPrefix, PrefixBuilder, RDF, RDFOps}

object SecurityPrefix:
   def apply[Rdf <: RDF](using ops: RDFOps[Rdf]) = new SecurityPrefix(ops)

class SecurityPrefix[Rdf <: RDF](ops: RDFOps[Rdf])
    extends PrefixBuilder("security", "https://w3id.org/security/v1#")(ops):
   val controller   = apply("controller")
   val publicKeyJwk = apply("publicKeyJwk")
