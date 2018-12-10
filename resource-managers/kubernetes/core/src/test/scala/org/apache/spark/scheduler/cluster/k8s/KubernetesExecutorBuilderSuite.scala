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
package org.apache.spark.scheduler.cluster.k8s

import scala.collection.JavaConverters._

import io.fabric8.kubernetes.api.model.{Config => _, _}
import io.fabric8.kubernetes.client.KubernetesClient
import org.mockito.Mockito.{mock, never, verify}

import org.apache.spark.{SecurityManager, SparkConf, SparkFunSuite}
import org.apache.spark.deploy.k8s._
import org.apache.spark.deploy.k8s.Constants._
import org.apache.spark.deploy.k8s.features._
import org.apache.spark.deploy.k8s.submit.PodBuilderSuiteUtils
import org.apache.spark.util.SparkConfWithEnv

class KubernetesExecutorBuilderSuite extends SparkFunSuite {
  private val BASIC_STEP_TYPE = "basic"
  private val SECRETS_STEP_TYPE = "mount-secrets"
  private val ENV_SECRETS_STEP_TYPE = "env-secrets"
  private val LOCAL_DIRS_STEP_TYPE = "local-dirs"
  private val HADOOP_CONF_STEP_TYPE = "hadoop-conf-step"
  private val HADOOP_SPARK_USER_STEP_TYPE = "hadoop-spark-user"
  private val KERBEROS_CONF_STEP_TYPE = "kerberos-step"
  private val MOUNT_VOLUMES_STEP_TYPE = "mount-volumes"

  private val secMgr = new SecurityManager(new SparkConf(false))

  private val basicFeatureStep = KubernetesFeaturesTestUtils.getMockConfigStepForStepType(
    BASIC_STEP_TYPE, classOf[BasicExecutorFeatureStep])
  private val mountSecretsStep = KubernetesFeaturesTestUtils.getMockConfigStepForStepType(
    SECRETS_STEP_TYPE, classOf[MountSecretsFeatureStep])
  private val envSecretsStep = KubernetesFeaturesTestUtils.getMockConfigStepForStepType(
    ENV_SECRETS_STEP_TYPE, classOf[EnvSecretsFeatureStep])
  private val localDirsStep = KubernetesFeaturesTestUtils.getMockConfigStepForStepType(
    LOCAL_DIRS_STEP_TYPE, classOf[LocalDirsFeatureStep])
  private val hadoopConfStep = KubernetesFeaturesTestUtils.getMockConfigStepForStepType(
    HADOOP_CONF_STEP_TYPE, classOf[HadoopConfExecutorFeatureStep])
  private val hadoopSparkUser = KubernetesFeaturesTestUtils.getMockConfigStepForStepType(
    HADOOP_SPARK_USER_STEP_TYPE, classOf[HadoopSparkUserExecutorFeatureStep])
  private val kerberosConf = KubernetesFeaturesTestUtils.getMockConfigStepForStepType(
    KERBEROS_CONF_STEP_TYPE, classOf[KerberosConfExecutorFeatureStep])
  private val mountVolumesStep = KubernetesFeaturesTestUtils.getMockConfigStepForStepType(
    MOUNT_VOLUMES_STEP_TYPE, classOf[MountVolumesFeatureStep])

  private val builderUnderTest = new KubernetesExecutorBuilder(
    (_, _) => basicFeatureStep,
    _ => mountSecretsStep,
    _ => envSecretsStep,
    _ => localDirsStep,
    _ => mountVolumesStep,
    _ => hadoopConfStep,
    _ => kerberosConf,
    _ => hadoopSparkUser)

  test("Basic steps are consistently applied.") {
    val conf = KubernetesTestConf.createExecutorConf()
    validateStepTypesApplied(
      builderUnderTest.buildFromFeatures(conf, secMgr), BASIC_STEP_TYPE, LOCAL_DIRS_STEP_TYPE)
  }

  test("Apply secrets step if secrets are present.") {
    val conf = KubernetesTestConf.createExecutorConf(
      secretEnvNamesToKeyRefs = Map("secret-name" -> "secret-key"),
      secretNamesToMountPaths = Map("secret" -> "secretMountPath"))
    validateStepTypesApplied(
      builderUnderTest.buildFromFeatures(conf, secMgr),
      BASIC_STEP_TYPE,
      LOCAL_DIRS_STEP_TYPE,
      SECRETS_STEP_TYPE,
      ENV_SECRETS_STEP_TYPE)
  }

  test("Apply volumes step if mounts are present.") {
    val volumeSpec = KubernetesVolumeSpec(
      "volume",
      "/tmp",
      "",
      false,
      KubernetesHostPathVolumeConf("/checkpoint"))
    val conf = KubernetesTestConf.createExecutorConf(
      volumes = Seq(volumeSpec))
    validateStepTypesApplied(
      builderUnderTest.buildFromFeatures(conf, secMgr),
      BASIC_STEP_TYPE,
      LOCAL_DIRS_STEP_TYPE,
      MOUNT_VOLUMES_STEP_TYPE)
  }

  test("Apply basicHadoop step if HADOOP_CONF_DIR is defined") {
    // HADOOP_DELEGATION_TOKEN
    val conf = KubernetesTestConf.createExecutorConf(
      sparkConf = new SparkConfWithEnv(Map("HADOOP_CONF_DIR" -> "/var/hadoop-conf"))
        .set(HADOOP_CONFIG_MAP_NAME, "hadoop-conf-map-name")
        .set(KRB5_CONFIG_MAP_NAME, "krb5-conf-map-name"))
    validateStepTypesApplied(
      builderUnderTest.buildFromFeatures(conf, secMgr),
      BASIC_STEP_TYPE,
      LOCAL_DIRS_STEP_TYPE,
      HADOOP_CONF_STEP_TYPE,
      HADOOP_SPARK_USER_STEP_TYPE)
  }

  test("Apply kerberos step if DT secrets created") {
    val conf = KubernetesTestConf.createExecutorConf(
      sparkConf = new SparkConf(false)
        .set(HADOOP_CONFIG_MAP_NAME, "hadoop-conf-map-name")
        .set(KRB5_CONFIG_MAP_NAME, "krb5-conf-map-name")
        .set(KERBEROS_SPARK_USER_NAME, "spark-user")
        .set(KERBEROS_DT_SECRET_NAME, "dt-secret")
        .set(KERBEROS_DT_SECRET_KEY, "dt-key" ))
    validateStepTypesApplied(
      builderUnderTest.buildFromFeatures(conf, secMgr),
      BASIC_STEP_TYPE,
      LOCAL_DIRS_STEP_TYPE,
      HADOOP_CONF_STEP_TYPE,
      KERBEROS_CONF_STEP_TYPE)
  }

  private def validateStepTypesApplied(resolvedPod: SparkPod, stepTypes: String*): Unit = {
    assert(resolvedPod.pod.getMetadata.getLabels.asScala.keys.toSet === stepTypes.toSet)
  }

  test("Starts with empty executor pod if template is not specified") {
    val kubernetesClient = mock(classOf[KubernetesClient])
    val executorBuilder = KubernetesExecutorBuilder.apply(kubernetesClient, new SparkConf())
    verify(kubernetesClient, never()).pods()
  }

  test("Starts with executor template if specified") {
    val kubernetesClient = PodBuilderSuiteUtils.loadingMockKubernetesClient()
    val sparkConf = new SparkConf(false)
      .set("spark.driver.host", "https://driver.host.com")
      .set(Config.CONTAINER_IMAGE, "spark-executor:latest")
      .set(Config.KUBERNETES_EXECUTOR_PODTEMPLATE_FILE, "template-file.yaml")
    val kubernetesConf = KubernetesTestConf.createExecutorConf(
      sparkConf = sparkConf,
      driverPod = Some(new PodBuilder()
        .withNewMetadata()
          .withName("driver")
          .endMetadata()
        .build()))
    val sparkPod = KubernetesExecutorBuilder(kubernetesClient, sparkConf)
      .buildFromFeatures(kubernetesConf, secMgr)
    PodBuilderSuiteUtils.verifyPodWithSupportedFeatures(sparkPod)
  }
}
