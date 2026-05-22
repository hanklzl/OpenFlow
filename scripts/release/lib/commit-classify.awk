# Input lines: "<full-sha>\t<commit subject>"
# Output blocks per category, separated by category headers
BEGIN {
    FS = "\t"
    cats[1] = "feat:#### 新功能"
    cats[2] = "fix:#### 修复"
    cats[3] = "perf:#### 性能"
    cats[4] = "refactor:#### 重构"
    cats[5] = "docs:#### 文档"
    cats[6] = "test:#### 测试"
    cats[7] = "chore:#### 杂项"
    cats[8] = "merge:#### 合并"
    n = 8
}
{
    matched = 0
    for (i = 1; i <= n; i++) {
        split(cats[i], pair, ":")
        prefix = pair[1]
        if (match($2, "^" prefix "(\\(|:)")) {
            short = substr($1, 1, 7)
            bucket[i] = bucket[i] "- " $2 " (" short ")\n"
            matched = 1
            break
        }
    }
    if (!matched) {
        short = substr($1, 1, 7)
        bucket[0] = bucket[0] "- " $2 " (" short ")\n"
    }
}
END {
    for (i = 1; i <= n; i++) {
        if (bucket[i] != "") {
            split(cats[i], pair, ":")
            print pair[2]
            printf "%s", bucket[i]
            print ""
        }
    }
    if (bucket[0] != "") {
        print "#### 其它"
        printf "%s", bucket[0]
    }
}
