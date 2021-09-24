pipeline {
  agent {
    kubernetes {
      defaultContainer 'infra-tools'
      yaml """
        apiVersion: v1
        kind: Pod
        metadata:
          labels:
            jenkins-pipeline: ncds-terraform-providers
        spec:
          containers:
          - name: infra-tools
            image: nexus.np.ncds.uk:4444/infrastructure/infra-tools-13:latest
            command:
            - cat
            tty: true
          - name: tfsec
            image: nexus.np.ncds.uk:4444/infrastructure/tfsec:latest
            command:
            - cat
            tty: true
      """
    }
  }

  options {
    buildDiscarder(
      logRotator(
        numToKeepStr: '20',
        artifactNumToKeepStr: '10'
      )
    )
    ansiColor('xterm')
    gitLabConnection("ncds-gitlab")
  }

  environment {
    AWS_DEV           = '484037285128'
    AWS_TOOLING       = '202696833239'
    SESSION_NAME      = 'COPO-Infrastructure'
    AWS_ROLE_TF_STATE = 'COPOState'
  }

  stages {
    stage("Code Analysis") {
      steps {
        container("tfsec") {
          updateGitlabCommitStatus name: "build", state: "pending"
          sh """
            tfsec .
          """
        }
      }
    }

    stage('Select Environment') {
      when {
        branch 'master'
      }
      steps {
        script {
          try {
            timeout(time: 15, unit: 'MINUTES') {
              env.SUB_ENVIRONMENT = input message: 'User input required',
              parameters: [choice(name: 'Select the environment to deploy to', choices: 'feature\ndev\ndev1\ndev2\ndev3\nsit-small', description: 'Choose "yes" if you want to continue')]
            }
          } catch (Exception e) {
           env.SUB_ENVIRONMENT = 'dev'
          }
        }
      }
    }

    stage('Terraform') {
      options {
        lock('copo-infrastructure')
      }
      stages {
        stage('Terraform Plan Changes') {
          steps {
            script {
              if ( env.SUB_ENVIRONMENT == null ) {
                env.SUB_ENVIRONMENT = 'dev'
              }
            }
            withCredentials([sshUserPrivateKey(credentialsId: "gitlab-ssh-jenkins", keyFileVariable: 'keyfile')]) {
              withAWS(role: 'TerraformBuild', roleAccount: "$AWS_DEV", roleSessionName: "${SESSION_NAME}") {
                sh """
                  export GIT_SSH_COMMAND="ssh -i $keyfile -o StrictHostKeyChecking=no"
                  
                  terraform init \
                      -backend-config "role_arn=arn:aws:iam::${AWS_TOOLING}:role/${AWS_ROLE_TF_STATE}" \
                      -backend-config key="copo/infrastructure/${env.SUB_ENVIRONMENT}/terraform.tfstate"

                  terraform plan -out terraform-plan -var "sub_environment=${env.SUB_ENVIRONMENT}"
                """
              }
            }
          }
        }

        stage('Terraform Plan Approval') {
          when {
            branch 'master'
          }
          steps {
            timeout(time: 15, unit: 'MINUTES') {
              script {
                env.APPLY_TERRAFORM = input message: 'User input required',
                  parameters: [choice(name: 'Confirm you would like to APPLY the changes', choices: 'no\nyes', description: 'Choose "yes" if you want to continue')]
              }
            }
          }
        }

        stage('Terraform Apply Changes') {
          when {
            branch 'master'
            anyOf {
              environment name: 'APPLY_TERRAFORM', value: 'yes'
            }
          }
          steps {
            withAWS(role: 'TerraformBuild', roleAccount: "$AWS_DEV", roleSessionName: "${SESSION_NAME}") {
              sh """
                terraform apply terraform-plan
              """
            }
          }
        }
      }
    }
  }

  post {
    always {
      notifications(channel: '#cicd_infra_jenkins')
      cleanWs()
    }
  }
}