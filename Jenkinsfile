// CD for Blixa (ColDP editor) -> GBIF dev/prod. Place at the root of the `blixa` repo.
// Backend builds with the agent's native JDK 25 + Maven; only npm runs in Docker (node:24),
// matching the col-checklistbank job. Deploy = rsync over ssh as jenkins-deploy + service restart.
pipeline {
  agent any

  parameters {
    choice(name: 'TARGET', choices: ['dev', 'prod'],
           description: 'Deploy environment (dev = apps.dev.checklistbank.org)')
  }

  options { timestamps(); disableConcurrentBuilds() }

  // Managed tool names as registered in this Jenkins (same as the other CoL Java jobs).
  // Puts mvn on PATH and pins the build to JDK 25 (Blixa does not compile on 21).
  tools {
    maven 'Maven 3.9.9'
    jdk 'LibericaJDK25'
  }

  environment {
    DEPLOY_USER = 'jenkins-deploy'
    APP_DOCROOT = '/var/www/html/blixa'
  }

  stages {
    stage('Build') {
      parallel {
        stage('Backend jar') {
          steps {
            // Native JDK 25 + Maven on the agent (no Docker); reuses the agent's ~/.m2 cache.
            sh 'cd backend && mvn -B -DskipTests clean package'
          }
        }
        stage('Frontend SPA') {
          steps {
            // npm in Docker, like the col-checklistbank job. Do NOT set NODE_ENV=production:
            // that makes `npm ci` skip devDependencies (typescript/vite), and the build needs
            // `tsc`/`vite`. `vite build` still emits a production bundle regardless of NODE_ENV.
            sh '''
              docker run --rm --user "$(id -u)" \
                -v "$PWD":/usr/src/app -w /usr/src/app/frontend \
                -e npm_config_cache=/usr/src/app/frontend/.npm \
                node:24 sh -c "npm ci && npm run build"
            '''
          }
        }
      }
    }

    stage('Deploy') {
      when { branch 'main' }
      // No sshagent/credential: the Jenkins user's own SSH key is already authorized as
      // jenkins-deploy on the VM (same setup the col-checklistbank job relies on).
      steps {
        sh '''
          set -eu
          case "$TARGET" in
            dev)  HOST=apps.dev.checklistbank.org ;;
            prod) HOST=apps.checklistbank.org ;;
            *)    echo "Unknown TARGET: $TARGET"; exit 1 ;;
          esac
          SSHOPT="-o StrictHostKeyChecking=accept-new"
          SSH="ssh $SSHOPT ${DEPLOY_USER}@${HOST}"

          echo "== Frontend -> ${HOST}:${APP_DOCROOT} =="
          rsync -rt --delete -e "ssh $SSHOPT" \
            frontend/dist/ ${DEPLOY_USER}@${HOST}:${APP_DOCROOT}/

          echo "== Backend jar -> staging =="
          rsync -t -e "ssh $SSHOPT" \
            backend/target/blixa-backend-*.jar \
            ${DEPLOY_USER}@${HOST}:/tmp/blixa-backend.jar

          echo "== Install jar as col + restart service =="
          # jenkins-deploy has `(col) NOPASSWD: ALL`, so install the jar as the col user;
          # the systemctl restart is granted separately (jenkins-deploy-col-blixa sudoers).
          $SSH 'sudo -u col install -m 644 /tmp/blixa-backend.jar /home/col/bin/blixa/blixa-backend.jar && rm -f /tmp/blixa-backend.jar'
          $SSH sudo /usr/bin/systemctl restart col-blixa.service

          echo "== Health check =="
          $SSH 'curl -fsS --retry 10 --retry-delay 3 --retry-connrefused \
                  http://127.0.0.1:8111/api/ping' && echo " OK"
        '''
      }
    }
  }

  post {
    success { echo "Deployed to ${params.TARGET}" }
    failure { echo "Build/deploy failed for ${params.TARGET}" }
  }
}
