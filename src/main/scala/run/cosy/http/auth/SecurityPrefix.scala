/*
 * Copyright 2021 Henry Story
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package run.cosy.http.auth

import org.w3.banana.{FOAFPrefix, PrefixBuilder, RDF, RDFOps}

object SecurityPrefix:
   def apply[Rdf <: RDF](using ops: RDFOps[Rdf]) = new SecurityPrefix(ops)

/**
  * Note:  the security prefix https://w3id.org/security/v1# is not a namespace!
  * That is a context document for rdfa, containing shortcuts for many different names
  * coming from different namespaces.
  * TODO: we need something like the Prefix class to model these!
  *
  * @param ops
  * @tparam Rdf
  */
class SecurityPrefix[Rdf <: RDF](ops: RDFOps[Rdf])
    extends PrefixBuilder("security", "https://w3id.org/security#")(ops):
   val controller   = apply("controller")
   val publicKeyJwk = apply("publicKeyJwk")
