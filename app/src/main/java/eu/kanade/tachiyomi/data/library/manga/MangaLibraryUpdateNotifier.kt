package eu.kanade.tachiyomi.data.library.manga

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import coil.imageLoader
import coil.request.ImageRequest
import coil.transform.CircleCropTransformation
import eu.kanade.presentation.util.formatChapterNumber
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.core.Constants
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.download.manga.MangaDownloader
import eu.kanade.tachiyomi.data.notification.NotificationHandler
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.util.lang.chop
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.getBitmapOrNull
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import tachiyomi.core.util.lang.launchUI
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.items.chapter.model.Chapter
import uy.kohesive.injekt.injectLazy
import java.math.RoundingMode
import java.text.NumberFormat

class MangaLibraryUpdateNotifier(private val context: Context) {

    private val preferences: SecurityPreferences by injectLazy()
    private val percentFormatter = NumberFormat.getPercentInstance().apply {
        roundingMode = RoundingMode.DOWN
        maximumFractionDigits = 0
    }

    /**
     * Pending intent of action that cancels the library update
     */
    private val cancelIntent by lazy {
        NotificationReceiver.cancelLibraryUpdatePendingBroadcast(context)
    }

    /**
     * Bitmap of the app for notifications.
     */
    private val notificationBitmap by lazy {
        BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)
    }

    /**
     * Cached progress notification to avoid creating a lot.
     */
    val progressNotificationBuilder by lazy {
        context.notificationBuilder(Notifications.CHANNEL_LIBRARY_PROGRESS) {
            setContentTitle(context.getString(R.string.app_name))
            setSmallIcon(R.drawable.ic_refresh_24dp)
            setLargeIcon(notificationBitmap)
            setOngoing(true)
            setOnlyAlertOnce(true)
            addAction(
                R.drawable.ic_close_24dp,
                context.getString(R.string.action_cancel),
                cancelIntent,
            )
        }
    }

    /**
     * Shows the notification containing the currently updating manga and the progress.
     *
     * @param manga the manga that are being updated.
     * @param current the current progress.
     * @param total the total progress.
     */
    fun showProgressNotification(manga: List<Manga>, current: Int, total: Int) {
        progressNotificationBuilder
            .setContentTitle(
                context.getString(
                    R.string.notification_updating_progress,
                    percentFormatter.format(current.toFloat() / total),
                ),
            )

        if (!preferences.hideNotificationContent().get()) {
            val updatingText = manga.joinToString("\n") { it.title.chop(40) }
            progressNotificationBuilder.setStyle(NotificationCompat.BigTextStyle().bigText(updatingText))
        }

        context.notify(
            Notifications.ID_LIBRARY_PROGRESS,
            progressNotificationBuilder
                .setProgress(total, current, false)
                .build(),
        )
    }

    fun showQueueSizeWarningNotification() {
        context.notify(
            Notifications.ID_LIBRARY_SIZE_WARNING,
            Notifications.CHANNEL_LIBRARY_PROGRESS,
        ) {
            setContentTitle(context.getString(R.string.label_warning))
            setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(context.getString(R.string.notification_size_warning)),
            )
            setSmallIcon(R.drawable.ic_warning_white_24dp)
            setTimeoutAfter(MangaDownloader.WARNING_NOTIF_TIMEOUT_MS)
            setContentIntent(NotificationHandler.openUrl(context, HELP_WARNING_URL))
        }
    }

    /**
     * Shows notification containing update entries that failed with action to open full log.
     *
     * @param failed Number of entries that failed to update.
     * @param uri Uri for error log file containing all titles that failed.
     */
    fun showUpdateErrorNotification(failed: Int, uri: Uri) {
        if (failed == 0) {
            return
        }

        context.notify(
            Notifications.ID_LIBRARY_ERROR,
            Notifications.CHANNEL_LIBRARY_ERROR,
        ) {
            setContentTitle(context.resources.getString(R.string.notification_update_error, failed))
            setContentText(context.getString(R.string.action_show_errors))
            setSmallIcon(R.drawable.ic_ani)

            setContentIntent(NotificationReceiver.openErrorLogPendingActivity(context, uri))
        }
    }

    /**
     * Shows notification containing update entries that were skipped.
     *
     * @param skipped Number of entries that were skipped during the update.
     */
    fun showUpdateSkippedNotification(skipped: Int) {
        if (skipped == 0) {
            return
        }

        context.notify(
            Notifications.ID_LIBRARY_SKIPPED,
            Notifications.CHANNEL_LIBRARY_SKIPPED,
        ) {
            setContentTitle(
                context.resources.getString(R.string.notification_update_skipped, skipped),
            )
            setContentText(context.getString(R.string.learn_more))
            setSmallIcon(R.drawable.ic_ani)
            setContentIntent(NotificationHandler.openUrl(context, HELP_SKIPPED_MANGA_URL))
        }
    }

    /**
     * Shows the notification containing the result of the update done by the service.
     *
     * @param updates a list of manga with new updates.
     */
    fun showUpdateNotifications(updates: List<Pair<Manga, Array<Chapter>>>) {
        // Parent group notification
        context.notify(
            Notifications.ID_NEW_CHAPTERS,
            Notifications.CHANNEL_NEW_CHAPTERS_EPISODES,
        ) {
            setContentTitle(context.getString(R.string.notification_new_chapters))
            if (updates.size == 1 && !preferences.hideNotificationContent().get()) {
                setContentText(updates.first().first.title.chop(NOTIF_MANGA_TITLE_MAX_LEN))
            } else {
                setContentText(
                    context.resources.getQuantityString(
                        R.plurals.notification_new_chapters_summary,
                        updates.size,
                        updates.size,
                    ),
                )

                if (!preferences.hideNotificationContent().get()) {
                    setStyle(
                        NotificationCompat.BigTextStyle().bigText(
                            updates.joinToString("\n") {
                                it.first.title.chop(NOTIF_MANGA_TITLE_MAX_LEN)
                            },
                        ),
                    )
                }
            }

            setSmallIcon(R.drawable.ic_ani)
            setLargeIcon(notificationBitmap)

            setGroup(Notifications.GROUP_NEW_CHAPTERS)
            setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            setGroupSummary(true)
            priority = NotificationCompat.PRIORITY_HIGH

            setContentIntent(getNotificationIntent())
            setAutoCancel(true)
        }

        // Per-manga notification
        if (!preferences.hideNotificationContent().get()) {
            launchUI {
                context.notify(
                    updates.map { (manga, chapters) ->
                        NotificationManagerCompat.NotificationWithIdAndTag(
                            manga.id.hashCode(),
                            createNewChaptersNotification(manga, chapters),
                        )
                    },
                )
            }
        }
    }

    private suspend fun createNewChaptersNotification(manga: Manga, chapters: Array<Chapter>): Notification {
        val icon = getMangaIcon(manga)
        return context.notificationBuilder(Notifications.CHANNEL_NEW_CHAPTERS_EPISODES) {
            setContentTitle(manga.title)

            val description = getNewChaptersDescription(chapters)
            setContentText(description)
            setStyle(NotificationCompat.BigTextStyle().bigText(description))

            setSmallIcon(R.drawable.ic_ani)

            if (icon != null) {
                setLargeIcon(icon)
            }

            setGroup(Notifications.GROUP_NEW_CHAPTERS)
            setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            priority = NotificationCompat.PRIORITY_HIGH

            // Open first chapter on tap
            setContentIntent(
                NotificationReceiver.openChapterPendingActivity(
                    context,
                    manga,
                    chapters.first(),
                ),
            )
            setAutoCancel(true)

            // Mark chapters as read action
            addAction(
                R.drawable.ic_glasses_24dp,
                context.getString(R.string.action_mark_as_read),
                NotificationReceiver.markAsViewedPendingBroadcast(
                    context,
                    manga,
                    chapters,
                    Notifications.ID_NEW_CHAPTERS,
                ),
            )
            // View chapters action
            addAction(
                R.drawable.ic_book_24dp,
                context.getString(R.string.action_view_chapters),
                NotificationReceiver.openChapterPendingActivity(
                    context,
                    manga,
                    Notifications.ID_NEW_CHAPTERS,
                ),
            )
            // Download chapters action
            // Only add the action when chapters is within threshold
            if (chapters.size <= MangaDownloader.CHAPTERS_PER_SOURCE_QUEUE_WARNING_THRESHOLD) {
                addAction(
                    android.R.drawable.stat_sys_download_done,
                    context.getString(R.string.action_download),
                    NotificationReceiver.downloadChaptersPendingBroadcast(
                        context,
                        manga,
                        chapters,
                        Notifications.ID_NEW_CHAPTERS,
                    ),
                )
            }
        }.build()
    }

    /**
     * Cancels the progress notification.
     */
    fun cancelProgressNotification() {
        context.cancelNotification(Notifications.ID_LIBRARY_PROGRESS)
    }

    private suspend fun getMangaIcon(manga: Manga): Bitmap? {
        val request = ImageRequest.Builder(context)
            .data(manga)
            .transformations(CircleCropTransformation())
            .size(NOTIF_MANGA_ICON_SIZE)
            .build()
        val drawable = context.imageLoader.execute(request).drawable
        return drawable?.getBitmapOrNull()
    }

    private fun getNewChaptersDescription(chapters: Array<Chapter>): String {
        val displayableChapterNumbers = chapters
            .filter { it.isRecognizedNumber }
            .sortedBy { it.chapterNumber }
            .map { formatChapterNumber(it.chapterNumber) }
            .toSet()

        return when (displayableChapterNumbers.size) {
            // No sensible chapter numbers to show (i.e. no chapters have parsed chapter number)
            0 -> {
                // "1 new chapter" or "5 new chapters"
                context.resources.getQuantityString(
                    R.plurals.notification_chapters_generic,
                    chapters.size,
                    chapters.size,
                )
            }
            // Only 1 chapter has a parsed chapter number
            1 -> {
                val remaining = chapters.size - displayableChapterNumbers.size
                if (remaining == 0) {
                    // "Chapter 2.5"
                    context.resources.getString(
                        R.string.notification_chapters_single,
                        displayableChapterNumbers.first(),
                    )
                } else {
                    // "Chapter 2.5 and 10 more"
                    context.resources.getString(
                        R.string.notification_chapters_single_and_more,
                        displayableChapterNumbers.first(),
                        remaining,
                    )
                }
            }
            // Everything else (i.e. multiple parsed chapter numbers)
            else -> {
                val shouldTruncate = displayableChapterNumbers.size > NOTIF_MAX_CHAPTERS
                if (shouldTruncate) {
                    // "Chapters 1, 2.5, 3, 4, 5 and 10 more"
                    val remaining = displayableChapterNumbers.size - NOTIF_MAX_CHAPTERS
                    val joinedChapterNumbers = displayableChapterNumbers.take(NOTIF_MAX_CHAPTERS).joinToString(
                        ", ",
                    )
                    context.resources.getQuantityString(
                        R.plurals.notification_chapters_multiple_and_more,
                        remaining,
                        joinedChapterNumbers,
                        remaining,
                    )
                } else {
                    // "Chapters 1, 2.5, 3"
                    context.resources.getString(
                        R.string.notification_chapters_multiple,
                        displayableChapterNumbers.joinToString(", "),
                    )
                }
            }
        }
    }

    /**
     * Returns an intent to open the main activity.
     */
    private fun getNotificationIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            action = Constants.SHORTCUT_UPDATES
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        // TODO: Change when implemented on Aniyomi website
        const val HELP_WARNING_URL =
            "https://aniyomi.org/docs/faq/library#why-am-i-warned-about-large-bulk-updates-and-downloads"
    }
}

private const val NOTIF_MAX_CHAPTERS = 5
private const val NOTIF_MANGA_TITLE_MAX_LEN = 45
private const val NOTIF_MANGA_ICON_SIZE = 192

// TODO: Change when implemented on Aniyomi website
private const val HELP_SKIPPED_MANGA_URL = "https://aniyomi.org/docs/faq/library#why-is-global-update-skipping-entries"