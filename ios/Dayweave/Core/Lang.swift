import Foundation

/** 界面语言跟随系统：简体中文显示中文，其余显示英文。整理输出的语言也随之切换。 */
enum Lang {
    static var english: Bool = {
        let preferred = Locale.preferredLanguages.first ?? "en"
        return !preferred.hasPrefix("zh")
    }()
}

func tr(_ zh: String, _ en: String) -> String { Lang.english ? en : zh }
