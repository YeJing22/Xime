#include "t9_string_utils.h"

#include <cctype>

namespace rime {

int CountLetters(const std::string& s) {
    int count = 0;
    for (char c : s) {
        if (std::isalpha(static_cast<unsigned char>(c))) ++count;
    }
    return count;
}

int CountLetters(const std::optional<std::string>& s) {
    if (!s.has_value()) return 0;
    return CountLetters(*s);
}

bool IsNonWordCandidate(const std::string& text) {
    if (text.empty()) return false;
    size_t i = 0;
    while (i < text.size()) {
        unsigned char c = static_cast<unsigned char>(text[i]);
        uint32_t cp = 0;
        size_t len = 1;
        if (c < 0x80) {
            cp = c;
        } else if ((c & 0xE0) == 0xC0) {
            cp = c & 0x1Fu; len = 2;
        } else if ((c & 0xF0) == 0xE0) {
            cp = c & 0x0Fu; len = 3;
        } else if ((c & 0xF8) == 0xF0) {
            cp = c & 0x07u; len = 4;
        } else {
            ++i;  // 非法首字节，跳过
            continue;
        }
        if (i + len > text.size()) break;
        for (size_t k = 1; k < len; ++k) {
            cp = (cp << 6) | (static_cast<unsigned char>(text[i + k]) & 0x3Fu);
        }
        i += len;

        if (cp < 0x80) {
            if (std::isalpha(static_cast<unsigned char>(cp))) return false;
        } else if ((cp >= 0x3400 && cp <= 0x4DBF) ||   // CJK 扩展 A
                   (cp >= 0x4E00 && cp <= 0x9FFF) ||   // CJK 基本区
                   (cp >= 0xF900 && cp <= 0xFAFF) ||   // CJK 兼容
                   (cp >= 0x3040 && cp <= 0x30FF) ||   // 日文假名
                   (cp >= 0xAC00 && cp <= 0xD7AF)) {   // 韩文
            return false;
        }
    }
    return true;
}

bool StartsWith(const std::string& s, const std::string& prefix) {
    return s.size() >= prefix.size() &&
           s.compare(0, prefix.size(), prefix) == 0;
}

bool EndsWith(const std::string& s, const std::string& suffix) {
    return s.size() >= suffix.size() &&
           s.compare(s.size() - suffix.size(), suffix.size(), suffix) == 0;
}

std::string Drop(const std::string& s, int n) {
    if (n <= 0) return s;
    if (n >= static_cast<int>(s.size())) return "";
    return s.substr(n);
}

std::string Take(const std::string& s, int n) {
    if (n <= 0) return "";
    if (n >= static_cast<int>(s.size())) return s;
    return s.substr(0, n);
}

std::string DropLast(const std::string& s, int n) {
    if (n <= 0) return s;
    if (n >= static_cast<int>(s.size())) return "";
    return s.substr(0, s.size() - n);
}

std::string FilterLetters(const std::string& s) {
    std::string result;
    for (char c : s) {
        if (std::isalpha(static_cast<unsigned char>(c))) result.push_back(c);
    }
    return result;
}

std::string JoinPinyins(const std::vector<SyllableOption>& sels) {
    std::string result;
    for (const auto& sel : sels) {
        result += sel.pinyin;
    }
    return result;
}

}  // namespace rime
