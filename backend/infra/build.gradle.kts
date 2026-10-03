// infra: DynamoDB（データベース）など、データの保存先とつなぐコードを置くプロジェクト（段階4の次のPRで SQLite に変える）。
// domain の約束（インターフェース）を、実際の外部サービスを使って実現する役割。

// DynamoDB Local が中で使う SQLite の「ネイティブライブラリ」（OSごとに作られた機械語の部品）。
// Javaのライブラリと違い、OSに合ったファイルを1つのフォルダに置き、その場所を教える必要がある。
// Maven Central にあるのは Linux(x86_64)・Windows(64bit)・Mac(Intel) 用だけ（Apple シリコンの Mac 用はない）。
val sqliteNatives: Configuration by configurations.creating
// DynamoDB Local を単独のサーバーとして動かすときに使うライブラリ一式（runDynamoDbLocal タスク用）
val dynamoDbLocalServer: Configuration by configurations.creating

dependencies {
    // implementation: このプロジェクトが使う（依存する）ライブラリやプロジェクト
    implementation(project(":backend:domain"))
    // platform(BOM): AWS SDK の部品どうしで、組み合わせて動くバージョンをそろえてくれる
    implementation(platform(libs.aws.sdk.bom))
    implementation(libs.aws.sdk.dynamodb)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    // テストでは DynamoDB Local をテストと同じプログラムの中で動かす（AWSにつながずに本物に近い動きを試せる）
    testImplementation(libs.dynamodb.local)
    testRuntimeOnly(libs.junit.platform.launcher)

    dynamoDbLocalServer(libs.dynamodb.local)

    val v = libs.versions.sqlite4java.get()
    sqliteNatives("com.almworks.sqlite4java:libsqlite4java-linux-amd64:$v@so")
    sqliteNatives("com.almworks.sqlite4java:sqlite4java-win32-x64:$v@dll")
    sqliteNatives("com.almworks.sqlite4java:libsqlite4java-osx:$v@dylib")
}

// ネイティブライブラリを build/sqlite4java にまとめる。
// ファイル名からバージョン番号を取り除く（sqlite4java は「libsqlite4java-linux-amd64.so」のような名前で探すため）
val copySqliteNatives by tasks.registering(Copy::class) {
    from(sqliteNatives)
    into(layout.buildDirectory.dir("sqlite4java"))
    rename("-${libs.versions.sqlite4java.get()}", "")
}
val sqliteNativeDir = layout.buildDirectory.dir("sqlite4java").map { it.asFile.absolutePath }

tasks.test {
    dependsOn(copySqliteNatives)
    // sqlite4java.library.path: ネイティブライブラリを探すフォルダ
    systemProperty("sqlite4java.library.path", sqliteNativeDir.get())
}

// 手元の動作確認用に、DynamoDB Local をサーバーとして起動するタスク（ポート8000）。
//   ./gradlew :backend:infra:runDynamoDbLocal
// データはリポジトリの一番上の .local/dynamodb に保存されるので、止めても消えない（消したいときはフォルダごと消す）。
tasks.register<JavaExec>("runDynamoDbLocal") {
    group = "application"
    description = "DynamoDB Local をポート8000で起動する（手元の動作確認用）"
    dependsOn(copySqliteNatives)
    classpath = dynamoDbLocalServer
    mainClass.set("software.amazon.dynamodb.services.local.main.ServerRunner")
    val dataDir = rootProject.layout.projectDirectory.dir(".local/dynamodb").asFile
    doFirst { dataDir.mkdirs() }
    jvmArgs("-Dsqlite4java.library.path=${sqliteNativeDir.get()}")
    // -sharedDb: 接続元の設定（リージョンなど）が違っても同じデータを見る。-dbPath: データの保存先
    args("-port", "8000", "-sharedDb", "-dbPath", dataDir.absolutePath)
}
