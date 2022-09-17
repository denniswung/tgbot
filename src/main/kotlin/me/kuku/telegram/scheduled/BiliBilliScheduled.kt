package me.kuku.telegram.scheduled

import me.kuku.telegram.config.TelegramBot
import me.kuku.telegram.config.TelegramConfig
import me.kuku.telegram.entity.BiliBiliService
import me.kuku.telegram.entity.Status
import me.kuku.telegram.logic.BiliBiliLogic
import me.kuku.telegram.logic.BiliBiliPojo
import me.kuku.utils.OkHttpKtUtils
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.telegram.telegrambots.meta.api.methods.send.SendMediaGroup
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto
import org.telegram.telegrambots.meta.api.methods.send.SendVideo
import org.telegram.telegrambots.meta.api.objects.InputFile
import org.telegram.telegrambots.meta.api.objects.media.InputMedia
import org.telegram.telegrambots.meta.api.objects.media.InputMediaPhoto
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

@Component
class BiliBilliScheduled(
    private val biliBiliService: BiliBiliService,
    private val telegramBot: TelegramBot,
    private val telegramConfig: TelegramConfig
) {

    private val liveMap = mutableMapOf<Long, MutableMap<Long, Boolean>>()
    private val userMap = mutableMapOf<Long, Long>()


    @Scheduled(cron = "0 23 3 * * ?")
    suspend fun sign() {
        val list = biliBiliService.findBySign(Status.ON)
        for (biliBiliEntity in list) {
            val firstRank = BiliBiliLogic.ranking()[0]
            BiliBiliLogic.report(biliBiliEntity, firstRank.aid, firstRank.cid, 300)
            BiliBiliLogic.share(biliBiliEntity, firstRank.aid)
            BiliBiliLogic.liveSign(biliBiliEntity)
        }
    }

    @Scheduled(fixedDelay = 2, timeUnit = TimeUnit.MINUTES)
    suspend fun liveMonitor() {
        val list = biliBiliService.findByLive(Status.ON)
        for (biliBiliEntity in list) {
            val result = BiliBiliLogic.followed(biliBiliEntity)
            if (result.failure()) continue
            val tgId = biliBiliEntity.tgId
            if (!liveMap.containsKey(tgId)) liveMap[tgId] = mutableMapOf()
            val map = liveMap[tgId]!!
            for (up in result.data()) {
                val id = up.id.toLong()
                val name = up.name
                val live = BiliBiliLogic.live(id.toString())
                if (live.id.isEmpty()) continue
                val b = live.status
                if (map.containsKey(id)) {
                    if (map[id] != b) {
                        map[id] = b
                        val msg = if (b) "直播啦！！" else "下播了！！"
                        telegramBot.silent().send("""
                            #哔哩哔哩开播提醒
                            哔哩哔哩开播提醒：
                            $name$msg
                            标题：${live.title}
                            链接：${live.url}
                        """.trimIndent(), tgId)
                    }
                } else map[id] = b
            }
        }
    }


    @Scheduled(fixedDelay = 2, timeUnit = TimeUnit.MINUTES)
    suspend fun userMonitor() {
        val biliBiliList = biliBiliService.findByPush(Status.ON)
        for (biliBiliEntity in biliBiliList) {
            val tgId = biliBiliEntity.tgId
            val result = BiliBiliLogic.friendDynamic(biliBiliEntity)
            val list = result.data ?: continue
            val newList = mutableListOf<BiliBiliPojo>()
            if (userMap.containsKey(tgId)) {
                val oldId = userMap[tgId]!!
                for (biliBiliPojo in list) {
                    if (biliBiliPojo.id.toLong() <= oldId) break
                    newList.add(biliBiliPojo)
                }
                for (biliBiliPojo in newList) {
                    val text = "#哔哩哔哩动态推送\n哔哩哔哩有新动态了！！\n${BiliBiliLogic.convertStr(biliBiliPojo)}"
                    val bvId = if (biliBiliPojo.bvId.isNotEmpty()) biliBiliPojo.bvId
                    else if (biliBiliPojo.forwardBvId.isNotEmpty()) biliBiliPojo.forwardBvId
                    else ""
                    if (bvId.isNotEmpty() && telegramConfig.url.isNotEmpty()) {
                        var file: File? = null
                        try {
                            file = BiliBiliLogic.videoByBvId(biliBiliEntity, biliBiliPojo.bvId)
                            file.inputStream().use { iis ->
                                val sendVideo =
                                    SendVideo(tgId.toString(), InputFile(iis, "${biliBiliPojo.bvId}.mp4"))
                                sendVideo.caption = text
                                telegramBot.execute(sendVideo)
                            }
                        }catch (e: Exception) {
                            telegramBot.silent().send("视频发送失败，转为文字发送\n$text", tgId)
                        } finally {
                            file?.delete()
                        }
                    } else if (biliBiliPojo.picList.isNotEmpty() || biliBiliPojo.forwardPicList.isNotEmpty()) {
                        val picList = biliBiliPojo.picList
                        picList.addAll(biliBiliPojo.forwardPicList)
                        if (picList.size == 1) {
                            val url = picList[0]
                            OkHttpKtUtils.getByteStream(url).use {
                                val sendPhoto = SendPhoto(tgId.toString(), InputFile(it, "${url.substring(url.lastIndexOf('/') + 1)}.jpg"))
                                sendPhoto.caption = text
                                telegramBot.execute(sendPhoto)
                            }
                        }
                        else {
                            val inputMediaList = mutableListOf<InputMedia>()
                            val ii = mutableListOf<InputStream>()
                            try {
                                for (imageUrl in picList) {
                                    val iis = OkHttpKtUtils.getByteStream(imageUrl)
                                    val name = imageUrl.substring(imageUrl.lastIndexOf('/') + 1)
                                    val mediaPhoto =
                                        InputMediaPhoto.builder().newMediaStream(iis).media("attach://$name")
                                            .mediaName(name).isNewMedia(true).build()
                                    mediaPhoto.caption = text
                                    mediaPhoto.captionEntities
                                    ii.add(iis)
                                    inputMediaList.add(mediaPhoto)
                                }
                                val sendMediaGroup = SendMediaGroup(tgId.toString(), inputMediaList)
                                telegramBot.execute(sendMediaGroup)
                            } finally {
                                ii.forEach { it.close() }
                            }
                        }
                    } else telegramBot.silent().send(text, tgId)
                }
            }
            userMap[tgId] = list[0].id.toLong()
        }
    }

}