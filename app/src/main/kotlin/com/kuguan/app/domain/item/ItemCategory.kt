package com.kuguan.app.domain.item

/** 搜索页的轻量分类：用户保存的类别优先，旧档案按名称推断，不迁移数据库。 */
object ItemCategory {
    private val rules = linkedMapOf(
        "工具五金" to listOf("螺丝", "螺母", "钉", "扳手", "钳", "锤", "钻", "胶带", "电工", "刀具", "卷尺"),
        "电子数码" to listOf("电池", "充电", "数据线", "耳机", "手机", "电脑", "鼠标", "键盘", "相机", "插座"),
        "厨房餐具" to listOf("碗", "盘", "锅", "筷", "勺", "杯", "保鲜", "厨房", "餐具", "调料"),
        "衣物配饰" to listOf("衣", "裤", "袜", "鞋", "帽", "围巾", "手套", "包包", "腰带"),
        "清洁护理" to listOf("洗", "清洁", "纸巾", "牙刷", "牙膏", "毛巾", "肥皂", "拖把"),
        "文具资料" to listOf("笔", "本子", "文件", "书", "纸张", "便签", "胶水", "尺子"),
        "药品健康" to listOf("药", "绷带", "体温", "口罩", "创可贴", "消毒"),
        "户外运动" to listOf("帐篷", "登山", "羽毛球", "篮球", "跳绳", "泳", "运动"),
    )

    fun of(name: String, savedCategory: String?): String {
        val saved = savedCategory?.trim().orEmpty()
        if (saved.isNotEmpty()) return saved
        return rules.entries.firstOrNull { (_, words) -> words.any(name::contains) }?.key ?: "其他"
    }

    fun counts(items: List<Pair<String, String?>>): Map<String, Int> =
        items.groupingBy { (name, category) -> of(name, category) }.eachCount()
}
