package me.kuku.telegram.scheduled

import kotlinx.coroutines.delay
import me.kuku.telegram.config.TelegramBot
import me.kuku.telegram.entity.HostLocService
import me.kuku.telegram.entity.Status
import me.kuku.telegram.logic.HostLocLogic
import me.kuku.telegram.logic.HostLocPost
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import java.util.concurrent.TimeUnit

@Component
class HostLocScheduled(
    private val hostLocService: HostLocService,
    private val telegramBot: TelegramBot
) {
    private var locId = 0

    @Scheduled(fixedDelay = 2, timeUnit = TimeUnit.MINUTES)
    suspend fun locPush() {
        val list = HostLocLogic.post()
        if (list.isEmpty()) return
        val newList = mutableListOf<HostLocPost>()
        if (locId != 0) {
            for (hostLocPost in list) {
                if (hostLocPost.id <= locId) break
                newList.add(hostLocPost)
            }
        }
        locId = list[0].id
        for (hostLocPost in newList) {
            delay(3000)
            val hostLocList = hostLocService.findByPush(Status.ON)
            for (hostLocEntity in hostLocList) {
                val str = """
                    #HostLoc新帖推送
                    Loc有新帖了！！
                    标题：${hostLocPost.title}
                    昵称：${hostLocPost.name}
                    链接：${hostLocPost.url}
                    内容：${HostLocLogic.postContent(hostLocPost.url, hostLocEntity.cookie)}
                """.trimIndent()
                val sendMessage = SendMessage(hostLocEntity.tgId.toString(), str)
                telegramBot.execute(sendMessage)
            }
        }
    }

    @Scheduled(cron = "0 12 4 * * ?")
    suspend fun sign() {
        val list = hostLocService.findBySign(Status.ON)
        for (hostLocEntity in list) {
            HostLocLogic.sign(hostLocEntity.cookie)
        }
    }

}