/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.kafka010

import java.util.UUID

import org.apache.hadoop.security.{Credentials, UserGroupInformation}
import org.apache.hadoop.security.token.Token
import org.scalatest.BeforeAndAfterEach

import org.apache.spark.{SparkConf, SparkFunSuite}
import org.apache.spark.deploy.security.KafkaTokenUtil
import org.apache.spark.deploy.security.KafkaTokenUtil.KafkaDelegationTokenIdentifier

class KafkaSecurityHelperSuite extends SparkFunSuite with BeforeAndAfterEach {
  private val tokenId = "tokenId" + UUID.randomUUID().toString
  private val tokenPassword = "tokenPassword" + UUID.randomUUID().toString

  private var sparkConf: SparkConf = null

  override def beforeEach(): Unit = {
    super.beforeEach()
    sparkConf = new SparkConf()
  }

  override def afterEach(): Unit = {
    try {
      resetUGI
    } finally {
      super.afterEach()
    }
  }

  private def addTokenToUGI(): Unit = {
    val token = new Token[KafkaDelegationTokenIdentifier](
      tokenId.getBytes,
      tokenPassword.getBytes,
      KafkaTokenUtil.TOKEN_KIND,
      KafkaTokenUtil.TOKEN_SERVICE
    )
    val creds = new Credentials()
    creds.addToken(KafkaTokenUtil.TOKEN_SERVICE, token)
    UserGroupInformation.getCurrentUser.addCredentials(creds)
  }

  private def resetUGI: Unit = {
    UserGroupInformation.setLoginUser(null)
  }

  test("isTokenAvailable without token should return false") {
    assert(!KafkaSecurityHelper.isTokenAvailable())
  }

  test("isTokenAvailable with token should return true") {
    addTokenToUGI()

    assert(KafkaSecurityHelper.isTokenAvailable())
  }

  test("getTokenJaasParams with token should return scram module") {
    addTokenToUGI()

    val jaasParams = KafkaSecurityHelper.getTokenJaasParams(sparkConf)

    assert(jaasParams.contains("ScramLoginModule required"))
    assert(jaasParams.contains("tokenauth=true"))
    assert(jaasParams.contains(tokenId))
    assert(jaasParams.contains(tokenPassword))
  }
}
