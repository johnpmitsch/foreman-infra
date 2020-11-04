pipeline {
    options {
        timestamps()
        timeout(time: 3, unit: 'HOURS')
        ansiColor('xterm')
        buildDiscarder(logRotator(daysToKeepStr: '7'))
    }

    agent { label 'fast' }

    stages {
        stage('Setup Git Repos') {
            steps {
                deleteDir()
                git url: git_url, branch: git_ref
                script {
                    archive_git_hash()
                }

                dir('foreman') {
                   git url: "https://github.com/theforeman/foreman", branch: 'develop', poll: false, changelog: false
                }
            }
        }
        stage("Setup RVM") {
            steps {

                configureRVM(ruby)

            }
        }
        stage('Configure Environment') {
            steps {

                dir('foreman') {
                    addGem()
                    databaseFile(gemset())
                }

            }
        }
        stage('Configure Database') {
            steps {

                dir('foreman') {
                    configureDatabase(ruby)
                }

            }
        }
        stage('Run Tests') {
            parallel {
                stage('tests') {
                    steps {
                        dir('foreman') {
                            withRVM(['bundle exec rake jenkins:katello TESTOPTS="-v" --trace'], ruby)
                        }
                    }
                }
                stage('rubocop') {
                    steps {
                        dir('foreman') {
                            withRVM(['bundle exec rake katello:rubocop TESTOPTS="-v" --trace'], ruby)
                        }
                    }
                }
                stage('angular-ui') {
                    steps {
                        script {
                            dir('engines/bastion') {
                                sh "npm install"
                                sh "grunt ci"
                            }
                            dir('engines/bastion_katello') {
                                sh "npm install"
                                sh "grunt ci"
                            }
                        }
                    }
                }
                stage('react-ui and assets-precompile') {
                    // putting these together so npm install can be done in parallel and not block the other tests
                    steps {
                        dir('foreman') {
                            withRVM(["bundle exec npm install"], ruby)
                        }   
                        sh 'npm test'
                        dir('foreman') {
                            withRVM(['bundle exec rake plugin:assets:precompile[katello] RAILS_ENV=production DATABASE_URL=nulldb://nohost --trace'], ruby)
                        }   
                    }   
                }   
            }
            post {
                always {
                    dir('foreman') {
                        archiveArtifacts artifacts: "log/test.log"
                        junit keepLongStdio: true, testResults: 'jenkins/reports/unit/*.xml'
                    }
                }
            }
        }
        stage('Test db:seed') {
            steps {

                dir('foreman') {

                    withRVM(['bundle exec rake db:drop RAILS_ENV=test || true'], ruby)
                    withRVM(['bundle exec rake db:create RAILS_ENV=test'], ruby)
                    withRVM(['bundle exec rake db:migrate RAILS_ENV=test'], ruby)
                    withRVM(['bundle exec rake db:seed RAILS_ENV=test'], ruby)

                }

            }
        }
        stage('Build and Archive Source') {
            steps {
                dir(project_name) {
                    git url: git_url, branch: git_ref
                }
                script {
                    generate_sourcefiles(project_name: project_name, source_type: source_type)
                }
            }
        }
        stage('Build Packages') {
            steps {
                build(
                    job: "${project_name}-${git_ref}-package-release",
                    propagate: false,
                    wait: false
                )
            }
        }
    }

    post {
        failure {
            notifyDiscourse(env, "${project_name} source release pipeline failed:", currentBuild.description)
        }
        always {
            dir('foreman') {
                cleanup(ruby)
            }
            deleteDir()
        }
    }
}
