package com.example.janggiai.ui.about

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.janggiai.BuildConfig
import com.example.janggiai.engine.fsf.FairyStockfishBuildInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * About + open-source notices.  The GPL text and THIRD_PARTY_NOTICES are bundled as assets so the
 * notice is available offline, as the license requires for a distributed binary.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var showing by remember { mutableStateOf<String?>(null) }   // asset name currently expanded
    var text by remember { mutableStateOf("") }
    LaunchedEffect(showing) {
        val name = showing ?: return@LaunchedEffect
        text = withContext(Dispatchers.IO) { runCatching { context.assets.open("licenses/$name").bufferedReader().use { it.readText() } }.getOrElse { "파일을 읽을 수 없습니다: $name" } }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("정보") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") } })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("Janggi AI", style = MaterialTheme.typography.headlineSmall)
            Text("버전 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · 패키지 ${BuildConfig.APPLICATION_ID}", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            Text("이 앱은 인터넷 권한이 없으며 계정·광고·분석 도구를 사용하지 않습니다. 모든 계산은 기기 안에서 이루어지고, 기보는 앱 내부 저장소에만 저장됩니다.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            Text("장기 엔진", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text("${FairyStockfishBuildInfo.NAME} ${FairyStockfishBuildInfo.VERSION}", style = MaterialTheme.typography.bodyLarge)
            Text("commit ${FairyStockfishBuildInfo.COMMIT}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            Text("source ${FairyStockfishBuildInfo.SOURCE_URL}", style = MaterialTheme.typography.bodySmall)
            Text("tarball SHA-256 ${FairyStockfishBuildInfo.TARBALL_SHA256}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            Text("빌드 플래그: ${FairyStockfishBuildInfo.BUILD_FLAGS}", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Text("Fairy-Stockfish는 ${FairyStockfishBuildInfo.AUTHOR}의 저작물이며 ${FairyStockfishBuildInfo.LICENSE}에 따라 배포됩니다. " +
                "이 앱은 Fairy-Stockfish를 수정 없이 네이티브 라이브러리(libjanggi_engine.so)로 포함하여 배포하므로, 앱과 함께 배포되는 해당 소스코드 전체는 아래 링크와 앱 소스 저장소(android/engine/src/main/cpp/fairy-stockfish)에서 동일한 라이선스로 제공됩니다. " +
                "GPL은 이 프로그램이 어떠한 보증도 없이 제공됨을 명시합니다.", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { showing = if (showing == "GPL-3.0.txt") null else "GPL-3.0.txt" }) { Text(if (showing == "GPL-3.0.txt") "GPL-3.0 전문 닫기" else "GPL-3.0 전문 보기") }
            TextButton(onClick = { showing = if (showing == "THIRD_PARTY_NOTICES.md") null else "THIRD_PARTY_NOTICES.md" }) { Text(if (showing == "THIRD_PARTY_NOTICES.md") "제3자 고지 닫기" else "제3자 소프트웨어 고지 보기") }
            if (showing != null) {
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.height(16.dp))
            Text("승률 표시에 관하여", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text("표시되는 승률은 엔진의 센티폰 평가를 로지스틱 함수(K=300)로 변환한 추정치이며, 임의의 값이나 난수는 사용하지 않습니다. 실전 기보로 보정된 모델(calibration.json)을 넣으면 그 값이 사용됩니다.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(32.dp))
        }
    }
}
