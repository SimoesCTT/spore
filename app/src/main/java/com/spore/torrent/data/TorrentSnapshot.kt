package com.spore.torrent.data

data class TorrentSnapshot(
    val name: String = "",
    val progress: Float = 0f,
    val downloadRate: Int = 0,
    val uploadRate: Int = 0,
    val numPeers: Int = 0,
    val numSeeds: Int = 0,
    val stateName: String = "IDLE",  // renamed from `state` to avoid collision with st.state()
    val infoHash: String = "",
    val totalDone: Long = 0L,
    val totalWanted: Long = 0L
) {
    val isActive: Boolean
        get() = stateName == "DOWNLOADING" || stateName == "DOWNLOADING_METADATA" || stateName == "CHECKING_FILES"
}
