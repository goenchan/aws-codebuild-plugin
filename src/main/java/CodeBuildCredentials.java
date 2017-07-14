/*
 *     Copyright 2017 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License"). You may not use this file except in compliance with the License.
 *     A copy of the License is located at
 *
 *         http://aws.amazon.com/apache2.0/
 *
 *     or in the "license" file accompanying this file.
 *     This file is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and limitations under the License.
 *
 *     Portions copyright Copyright (c) 2015, CloudBees, Inc.
 */

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.BasicSessionCredentials;
import com.amazonaws.services.codebuild.AWSCodeBuildClient;
import com.amazonaws.services.codebuild.model.ListProjectsRequest;
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClient;
import com.amazonaws.services.securitytoken.model.AssumeRoleRequest;
import com.amazonaws.services.securitytoken.model.AssumeRoleResult;
import com.cloudbees.plugins.credentials.CredentialsDescriptor;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.impl.BaseStandardCredentials;
import hudson.Extension;
import hudson.util.FormValidation;
import lombok.Getter;
import lombok.Setter;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import java.util.UUID;

public class CodeBuildCredentials extends BaseStandardCredentials implements AWSCredentialsProvider {

    public static final long serialVersionUID = 555L;
    private static final int ERROR_MESSAGE_MAX_LENGTH = 178;

    @Getter @Setter private final String accessKey;
    @Getter @Setter private final String secretKey;
    @Getter @Setter private final String proxyHost;
    @Getter @Setter private final String proxyPort;
    @Getter @Setter private final String iamRoleArn;
    @Getter @Setter private final String externalId;

    @DataBoundConstructor
    public CodeBuildCredentials(CredentialsScope scope, String id, String description, String accessKey, String secretKey,
                                String proxyHost, String proxyPort, String iamRoleArn, String externalId) {
        super(scope, id, description);
        this.accessKey = Validation.sanitize(accessKey);
        this.secretKey = Validation.sanitize(secretKey);
        this.proxyHost = proxyHost;
        this.proxyPort = proxyPort;
        this.iamRoleArn = Validation.sanitize(iamRoleArn);
        this.externalId = externalId;
    }

    public String getCredentialsDescriptor() {
        if(accessKey.isEmpty() || secretKey.isEmpty()) {
            return Validation.defaultChainCredentials;
        } else {
           if(iamRoleArn.isEmpty()) {
               return Validation.basicAWSCredentials;
           } else {
               return Validation.IAMRoleCredentials + this.iamRoleArn;
           }
        }
    }

    @Override
    public AWSCredentials getCredentials() {
        AWSCredentialsProvider credentialsProvider = AWSClientFactory.getBasicCredentialsOrDefaultChain(accessKey, secretKey);
        AWSCredentials initialCredentials = credentialsProvider.getCredentials();

        if (iamRoleArn.isEmpty()) {
            return initialCredentials;
        } else {
            AssumeRoleRequest assumeRequest = new AssumeRoleRequest()
                    .withRoleArn(iamRoleArn)
                    .withExternalId(externalId)
                    .withDurationSeconds(3600)
                    .withRoleSessionName("CodeBuild-Jenkins-Plugin");

            AssumeRoleResult assumeResult = new AWSSecurityTokenServiceClient(initialCredentials).assumeRole(assumeRequest);

            return new BasicSessionCredentials(
                    assumeResult.getCredentials().getAccessKeyId(),
                    assumeResult.getCredentials().getSecretAccessKey(),
                    assumeResult.getCredentials().getSessionToken());
        }
    }

    @Override
    public void refresh() {

    }

    @Extension
    public static class DescriptorImpl extends CredentialsDescriptor {

        public String getDisplayName() {
            return "CodeBuild Credentials";
        }

        public FormValidation doCheckSecretKey(@QueryParameter("proxyHost") final String proxyHost,
                                               @QueryParameter("proxyPort") final String proxyPort,
                                               @QueryParameter("accessKey") final String accessKey,
                                               @QueryParameter("secretKey") final String secretKey) {

            try {
                AWSCredentials initialCredentials = AWSClientFactory.getBasicCredentialsOrDefaultChain(accessKey, secretKey).getCredentials();
                new AWSCodeBuildClient(initialCredentials, getClientConfiguration(proxyHost, proxyPort)).listProjects(new ListProjectsRequest());

            } catch (Exception e) {
                String errorMessage = e.getMessage();
                if(errorMessage.length() >= ERROR_MESSAGE_MAX_LENGTH) {
                    errorMessage = errorMessage.substring(ERROR_MESSAGE_MAX_LENGTH);
                }
                return FormValidation.error("Authorization failed: " + errorMessage);
            }
            return FormValidation.ok("AWS access and secret key authorization successful.");
        }

        public FormValidation doCheckIamRoleArn(@QueryParameter("proxyHost") final String proxyHost,
                                                @QueryParameter("proxyPort") final String proxyPort,
                                                @QueryParameter("accessKey") final String accessKey,
                                                @QueryParameter("secretKey") final String secretKey,
                                                @QueryParameter("iamRoleArn") final String iamRoleArn,
                                                @QueryParameter("externalId") final String externalId) {

            if (accessKey.isEmpty() || secretKey.isEmpty()) {
                return FormValidation.error("AWS access and secret keys are required to use an IAM role for authorization");
            }

            if(iamRoleArn.isEmpty()) {
                return FormValidation.ok();
            }

            try {

                AWSCredentials initialCredentials = new BasicAWSCredentials(accessKey, secretKey);

                AssumeRoleRequest assumeRequest = new AssumeRoleRequest()
                        .withRoleArn(iamRoleArn)
                        .withExternalId(externalId)
                        .withDurationSeconds(3600)
                        .withRoleSessionName("jenkins-codebuild-plugin");

                new AWSSecurityTokenServiceClient(initialCredentials, getClientConfiguration(proxyHost, proxyPort)).assumeRole(assumeRequest);

            } catch (Exception e) {
                String errorMessage = e.getMessage();
                if(errorMessage.length() >= ERROR_MESSAGE_MAX_LENGTH) {
                    errorMessage = errorMessage.substring(ERROR_MESSAGE_MAX_LENGTH);
                }
                return FormValidation.error("Authorization failed: " + errorMessage);
            }
            return FormValidation.ok("IAM role authorization successful.");
        }

        public String getNewUUID() {
            return UUID.randomUUID().toString();
        }

        private ClientConfiguration getClientConfiguration(String proxyHost, String proxyPort) {
            ClientConfiguration clientConfig = new ClientConfiguration();
            if (!proxyHost.isEmpty()) {
                clientConfig.withProxyHost(proxyHost);
            }
            if (!proxyPort.isEmpty()) {
                clientConfig.setProxyPort(Validation.parseInt(proxyPort));
            }
            return clientConfig;
        }
    }

}
