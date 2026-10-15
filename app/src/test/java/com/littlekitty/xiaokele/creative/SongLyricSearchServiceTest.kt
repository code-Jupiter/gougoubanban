package com.littlekitty.xiaokele.creative

import org.robolectric.annotation.Config
import com.littlekitty.xiaokele.core.common.error.AppError
import com.littlekitty.xiaokele.core.network.HttpRequest
import com.littlekitty.xiaokele.core.network.HttpTransport
import com.littlekitty.xiaokele.core.network.NetworkResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@Config(sdk = [33], application = android.app.Application::class)
class SongLyricSearchServiceTest {

    @Test
    fun returnsOnlyDisambiguatedMetadataAndShortExcerpt() = runBlocking {
        val transport = QueueTransport(
            listOf(
                NetworkResult.Success(
                    statusCode = 200,
                    headers = emptyMap(),
                    body = """{"results":[{"trackName":"夜航","artistName":"小狗乐队","trackViewUrl":"https://music.example/song"}]}""",
                ),
                NetworkResult.Success(
                    statusCode = 200,
                    headers = emptyMap(),
                    body = """{"lyrics":"风把灯影吹得很轻\n这一整夜都在远行"}""",
                ),
            ),
        )

        val result = PublicSongLyricSearchService(transport).search("像夜里走在风里", "小狗乐队")

        val match = (result as SongLyricSearchResult.Success).match
        assertEquals("夜航", match.songTitle)
        assertEquals("小狗乐队", match.artist)
        assertTrue(match.lyricExcerpt.length <= PublicSongLyricSearchService.MAX_CJK_EXCERPT_CHARS)
        assertTrue(match.internalSourceReference.isNotBlank())
        assertTrue(transport.requests.none { it.url.contains("play") })
    }

    @Test
    fun missingLyricsFailsClosedInsteadOfInventingMatch() = runBlocking {
        val result = PublicSongLyricSearchService(
            QueueTransport(
                listOf(
                    NetworkResult.Success(200, emptyMap(), """{"results":[{"trackName":"歌","artistName":"歌手"}]}"""),
                    NetworkResult.Success(404, emptyMap(), "{}"),
                ),
            ),
        ).search("不确定的歌")

        assertTrue(result is SongLyricSearchResult.Unavailable)
    }

    private class QueueTransport(private val responses: List<NetworkResult>) : HttpTransport {
        val requests = mutableListOf<HttpRequest>()
        private var index = 0

        override suspend fun execute(request: HttpRequest): NetworkResult {
            requests += request
            return responses.getOrElse(index++) {
                NetworkResult.Failure(AppError(com.littlekitty.xiaokele.core.common.error.ErrorCode.NETWORK_UNAVAILABLE))
            }
        }
    }
}
