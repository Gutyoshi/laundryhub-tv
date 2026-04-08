@rem Gradle startup script for Windows

@if "%DEBUG%"=="" @echo off

set DEFAULT_JVM_OPTS="-Xmx64m" "-Xms64m"

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
@rem This is normally unused
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%

set CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar

@rem Download wrapper jar if missing
if not exist "%APP_HOME%\gradle\wrapper\gradle-wrapper.jar" (
    echo Downloading Gradle Wrapper...
    mkdir "%APP_HOME%\gradle\wrapper" 2>NUL
    powershell -Command "Invoke-WebRequest -Uri 'https://raw.githubusercontent.com/gradle/gradle/v8.11.1/gradle/wrapper/gradle-wrapper.jar' -OutFile '%APP_HOME%\gradle\wrapper\gradle-wrapper.jar'"
)

@rem Execute Gradle
java %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% ^
  "-Dorg.gradle.appname=%APP_BASE_NAME%" ^
  -classpath "%CLASSPATH%" ^
  org.gradle.wrapper.GradleWrapperMain %*
