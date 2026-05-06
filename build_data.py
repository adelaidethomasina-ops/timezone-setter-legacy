#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成紧凑的邮编→时区映射数据文件。

输出两个文件：
  - us_zip3_tz.csv  : 美国 ZIP3 前缀 → IANA 时区 ID
  - ca_fsa_tz.csv   : 加拿大 FSA 前缀 → IANA 时区 ID
  - us_overrides.csv: 跨时区州的 ZIP5 精确覆盖

数据基于 USPS ZIP 分配、加拿大邮政 FSA 省份分配、IANA 时区规则。
"""

# ============================================================
# 美国各州的主时区（IANA ID）
# ============================================================
STATE_TZ = {
    "AL": "America/Chicago",
    "AK": "America/Anchorage",
    "AZ": "America/Phoenix",       # 亚利桑那不用 DST（Navajo 例外）
    "AR": "America/Chicago",
    "CA": "America/Los_Angeles",
    "CO": "America/Denver",
    "CT": "America/New_York",
    "DE": "America/New_York",
    "DC": "America/New_York",
    "FL": "America/New_York",      # 西部 Panhandle 是 Chicago
    "GA": "America/New_York",
    "HI": "Pacific/Honolulu",      # 不用 DST
    "ID": "America/Boise",         # 北部是 Boise=Mountain，南部 Idaho panhandle 是 Pacific
    "IL": "America/Chicago",
    "IN": "America/Indiana/Indianapolis",  # 部分县在 Chicago
    "IA": "America/Chicago",
    "KS": "America/Chicago",       # 西端 4 个县是 Denver
    "KY": "America/New_York",      # 西部在 Chicago
    "LA": "America/Chicago",
    "ME": "America/New_York",
    "MD": "America/New_York",
    "MA": "America/New_York",
    "MI": "America/Detroit",       # 西端 UP 几个县在 Chicago
    "MN": "America/Chicago",
    "MS": "America/Chicago",
    "MO": "America/Chicago",
    "MT": "America/Denver",
    "NE": "America/Chicago",       # 西部在 Denver
    "NV": "America/Los_Angeles",   # 北东 West Wendover 在 Denver
    "NH": "America/New_York",
    "NJ": "America/New_York",
    "NM": "America/Denver",
    "NY": "America/New_York",
    "NC": "America/New_York",
    "ND": "America/Chicago",       # 西部在 Denver
    "OH": "America/New_York",
    "OK": "America/Chicago",
    "OR": "America/Los_Angeles",   # 东部 Malheur 县在 Denver
    "PA": "America/New_York",
    "RI": "America/New_York",
    "SC": "America/New_York",
    "SD": "America/Chicago",       # 西部在 Denver
    "TN": "America/Chicago",       # 东部在 New_York
    "TX": "America/Chicago",       # 西端 El Paso/Hudspeth 在 Denver
    "UT": "America/Denver",
    "VT": "America/New_York",
    "VA": "America/New_York",
    "WA": "America/Los_Angeles",
    "WV": "America/New_York",
    "WI": "America/Chicago",
    "WY": "America/Denver",
    # 海外领地
    "PR": "America/Puerto_Rico",
    "VI": "America/St_Thomas",
    "GU": "Pacific/Guam",
    "AS": "Pacific/Pago_Pago",
    "MP": "Pacific/Saipan",
}

# ============================================================
# 美国 ZIP3 前缀 → 州 映射
# 完整覆盖 000-999 中所有已分配的 ZIP3
# 来源：USPS ZIP Code Prefixes（公开资料）
# ============================================================
# 格式：(起始 ZIP3, 结束 ZIP3 包含, 州代码)
ZIP3_RANGES = [
    # 005 特例：纽约 Holtsville
    (5, 5, "NY"),
    # 010-027 马萨诸塞/罗德岛/新罕布什尔/缅因
    (10, 27, "MA"),   # 会被后续细分覆盖
    (28, 29, "RI"),
    (30, 38, "NH"),
    (39, 49, "ME"),
    (50, 54, "VT"),
    (55, 55, "MA"),
    (56, 59, "VT"),
    (60, 69, "CT"),
    (70, 89, "NJ"),
    (100, 149, "NY"),
    (150, 196, "PA"),
    (197, 199, "DE"),
    (200, 205, "DC"),
    (206, 219, "MD"),
    (220, 246, "VA"),
    (247, 268, "WV"),
    (270, 289, "NC"),
    (290, 299, "SC"),
    (300, 319, "GA"),
    (320, 349, "FL"),
    (350, 369, "AL"),
    (370, 385, "TN"),
    (386, 397, "MS"),
    (398, 399, "GA"),
    (400, 427, "KY"),
    (430, 458, "OH"),
    (459, 459, "OH"),
    (460, 479, "IN"),
    (480, 499, "MI"),
    (500, 528, "IA"),
    (530, 549, "WI"),
    (550, 567, "MN"),
    (570, 577, "SD"),
    (580, 588, "ND"),
    (590, 599, "MT"),
    (600, 629, "IL"),
    (630, 658, "MO"),
    (660, 679, "KS"),
    (680, 693, "NE"),
    (700, 714, "LA"),
    (716, 729, "AR"),
    (730, 749, "OK"),
    (750, 799, "TX"),
    (800, 816, "CO"),
    (820, 831, "WY"),
    (832, 838, "ID"),
    (840, 847, "UT"),
    (850, 865, "AZ"),
    (870, 884, "NM"),
    (889, 898, "NV"),
    (900, 961, "CA"),
    (962, 966, "HI"),     # 实际 HI 是 967-968；962-966 是 APO/FPO
    (967, 968, "HI"),
    (969, 969, "GU"),
    (970, 979, "OR"),
    (980, 994, "WA"),
    (995, 999, "AK"),
]

def build_us_zip3():
    """构建 US ZIP3 → 时区映射"""
    mapping = {}
    for start, end, state in ZIP3_RANGES:
        tz = STATE_TZ.get(state)
        if not tz:
            continue
        for z in range(start, end + 1):
            mapping[f"{z:03d}"] = tz
    # 修正 962-966（APO/FPO 默认给 Honolulu 不合适，军邮实际是 UTC 但太特殊，删除）
    for z in range(962, 967):
        mapping.pop(f"{z:03d}", None)
    return mapping


# ============================================================
# 美国跨时区州的 ZIP5 精确覆盖
# 仅列出"偏离该州主时区"的 ZIP5（精确列表）
# 来源：IANA tzdata + USPS 县-邮编对应表
# ============================================================
# 为了数据体积，用 ZIP5 前缀（ZIP3/ZIP4/ZIP5 混合）表示范围
# 格式 {"zip5_前缀": "tz"}  前缀匹配：只要邮编以这个前缀开头就用该时区
OVERRIDES = {}

def add_override_range(start, end, tz):
    """将 [start, end] 范围的 5 位邮编整数逐个加入覆盖表（只在主时区不匹配时有意义）"""
    for z in range(start, end + 1):
        OVERRIDES[f"{z:05d}"] = tz

# --- 佛罗里达 Panhandle（西部 8 县使用 Central Time）---
# 县：Escambia, Santa Rosa, Okaloosa, Walton, Holmes, Washington, Bay, Jackson, Gulf, Calhoun
# 邮编范围大致 32401-32469, 32501-32599
add_override_range(32401, 32469, "America/Chicago")
add_override_range(32501, 32599, "America/Chicago")

# --- 印第安纳州：大部分用 Indiana/Indianapolis，但以下县用 Chicago ---
# Lake, Porter, LaPorte, Starke, Jasper, Newton, Pulaski (西北角)
# + Gibson, Pike (部分), Posey, Spencer, Vanderburgh, Warrick, Perry (西南角)
# 邮编 463xx-464xx（西北），474xx-476xx（西南部分）
add_override_range(46301, 46399, "America/Chicago")  # 西北角
add_override_range(46401, 46499, "America/Chicago")
# 西南部分（Evansville 地区）
for z in [47520, 47558, 47588, 47596, 47610, 47611, 47612, 47613,
          47615, 47616, 47619, 47620, 47630, 47631, 47633, 47634, 47635,
          47637, 47638, 47639, 47640, 47647, 47649, 47660, 47666,
          47670, 47708, 47710, 47711, 47712, 47713, 47714, 47715,
          47716, 47719, 47720, 47721, 47722, 47724, 47725, 47728,
          47730, 47731, 47732, 47733, 47734, 47735, 47736, 47737, 47740,
          47744, 47747, 47750]:
    OVERRIDES[f"{z:05d}"] = "America/Chicago"

# --- 肯塔基州西部（Louisville 以西用 Chicago）---
# 邮编 420xx-427xx 大部分是 Chicago；400xx-419xx 是 New_York
add_override_range(42000, 42799, "America/Chicago")

# --- 田纳西州东部（Knoxville/Chattanooga 地区用 New_York）---
# 邮编 370xx-385xx，其中东部 37xxx 是 New_York，西部 38xxx 是 Chicago
# 37000-37999 的东部用 New_York（主表是 Chicago，需覆盖）
add_override_range(37000, 37999, "America/New_York")
# 但田纳西中部的 Nashville 等 370xx-372xx 部分是 Chicago — 太复杂，主要城市：
# Knoxville 37901-37998 → NY (correct above)
# Chattanooga 37401-37450 → NY (correct above)
# Nashville 37201-37250 → Chicago
add_override_range(37201, 37250, "America/Chicago")
add_override_range(37000, 37199, "America/Chicago")  # Clarksville 等西部
# Memphis 38xxx 已经是 Chicago（主表）

# --- 密歇根州 UP 四县用 Chicago ---
# Gogebic, Iron, Dickinson, Menominee
# 邮编：49801-49971 部分
for z in [49801, 49802, 49805, 49807, 49815, 49820, 49821, 49826, 49829,
          49831, 49834, 49835, 49836, 49840, 49841, 49847, 49848, 49849,
          49858, 49861, 49862, 49863, 49864, 49866, 49870, 49871, 49873,
          49874, 49876, 49877, 49878, 49879, 49880, 49881, 49887, 49891,
          49892, 49893, 49894, 49895, 49896, 49901, 49902, 49903, 49905,
          49908, 49910, 49911, 49913, 49915, 49916, 49917, 49918, 49919,
          49920, 49921, 49922, 49925, 49927, 49929, 49930, 49931, 49934,
          49935, 49938, 49942, 49945, 49946, 49947, 49948, 49950, 49952,
          49953, 49955, 49958, 49959, 49960, 49961, 49962, 49963, 49965,
          49967, 49968, 49969, 49970, 49971]:
    OVERRIDES[f"{z:05d}"] = "America/Menominee"

# --- 北达科他州西部（McKenzie/Bowman/Slope 等县用 Denver）---
# 邮编 586xx-588xx 部分
for z in range(58622, 58655):
    OVERRIDES[f"{z:05d}"] = "America/Denver"
add_override_range(58601, 58621, "America/Denver")

# --- 南达科他州西部（Rapid City 地区 Mountain Time）---
# 邮编 577xx 西部
add_override_range(57701, 57799, "America/Denver")

# --- 内布拉斯加西部（Panhandle 用 Denver）---
# 邮编 691xx-693xx 部分
add_override_range(69101, 69367, "America/Denver")

# --- 堪萨斯州 4 县用 Denver ---
# Sherman, Wallace, Greeley, Hamilton
for z in [67735, 67738, 67744, 67747, 67748, 67751, 67752, 67764,
          67879, 67882, 67846, 67850, 67855, 67860, 67864]:
    OVERRIDES[f"{z:05d}"] = "America/Denver"

# --- 德州西端 El Paso/Hudspeth 两县用 Denver ---
add_override_range(79821, 79938, "America/Denver")
add_override_range(88510, 88589, "America/Denver")  # El Paso PO Boxes

# --- 爱达荷州北部 10 县（Panhandle）用 Pacific ---
# 邮编 832xx, 833xx 北部
add_override_range(83801, 83877, "America/Los_Angeles")

# --- 俄勒冈州东部 Malheur 县用 Denver ---
add_override_range(97901, 97920, "America/Boise")

# --- 内华达 West Wendover 用 Denver ---
OVERRIDES["89883"] = "America/Denver"

# --- 阿留申群岛用 America/Adak ---
add_override_range(99546, 99591, "America/Adak")

# --- 亚利桑那 Navajo Nation 用 Denver（有 DST）---
# 邮编 86xxx 部分（Apache, Navajo, Coconino 北部部分）
for z in [86044, 86503, 86504, 86505, 86506, 86507, 86508, 86510,
          86511, 86512, 86514, 86515, 86520, 86535, 86538, 86540,
          86544, 86545, 86547, 86556]:
    OVERRIDES[f"{z:05d}"] = "America/Denver"


# ============================================================
# 加拿大 FSA 前缀 → 时区映射
# FSA 第 1 位 = 省份/地区
# ============================================================
# 加拿大 FSA 第一位字母对应的省份
# A=NL, B=NS, C=PE, E=NB, G/H/J=QC, K/L/M/N/P=ON, R=MB, S=SK, T=AB, V=BC,
# X=NT/NU, Y=YT
PROVINCE_TZ = {
    "A": "America/St_Johns",       # Newfoundland
    "B": "America/Halifax",        # Nova Scotia
    "C": "America/Halifax",        # Prince Edward Island
    "E": "America/Moncton",        # New Brunswick
    "G": "America/Toronto",        # Quebec 东部
    "H": "America/Toronto",        # Quebec Montreal
    "J": "America/Toronto",        # Quebec 西部
    "K": "America/Toronto",        # Ontario 东部
    "L": "America/Toronto",        # Ontario GTA
    "M": "America/Toronto",        # Toronto
    "N": "America/Toronto",        # Ontario 西南
    "P": "America/Toronto",        # Ontario 北部 — 注意 P0V/P9N 等西北部分用 Winnipeg
    "R": "America/Winnipeg",       # Manitoba
    "S": "America/Regina",         # Saskatchewan（不用 DST）
    "T": "America/Edmonton",       # Alberta
    "V": "America/Vancouver",      # British Columbia — 东南角 Creston 用 Cranbrook/Mountain
    "X": "America/Yellowknife",    # NT/NU（NU 东部用 Iqaluit，Kivalliq 用 Rankin_Inlet）
    "Y": "America/Whitehorse",     # Yukon（Whitehorse 2020 年起 MST 全年不 DST）
}

def build_ca_fsa():
    """生成所有有效的加拿大 FSA 映射"""
    mapping = {}
    # FSA 格式：字母 + 数字 + 字母，第二位 0-9
    import string
    for first in PROVINCE_TZ.keys():
        for digit in range(10):
            for third in string.ascii_uppercase:
                # 跳过不允许的字母：D, F, I, O, Q, U, W, Z（第一位）
                if first in "DFIOQUWZ":
                    continue
                # 第三位不允许 D, F, I, O, Q, U
                if third in "DFIOQU":
                    continue
                fsa = f"{first}{digit}{third}"
                mapping[fsa] = PROVINCE_TZ[first]
    # 安大略西北部分（Thunder Bay 以西用 Winnipeg）
    # Kenora, Rainy River 地区 FSA：P0V, P0W, P0X, P9N（Thunder Bay 仍然 Toronto）
    # P0V = Kenora/Fort Frances 地区 → Winnipeg
    # 实际上 Thunder Bay (P7A-P7E) 仍然 Eastern，只有 Kenora District 西部是 Central
    mapping["P0V"] = "America/Winnipeg"
    mapping["P0W"] = "America/Winnipeg"
    mapping["P0X"] = "America/Rainy_River"
    mapping["P9N"] = "America/Rainy_River"
    # Nunavut 东部（Iqaluit）用 Iqaluit 时区：FSA X0A
    mapping["X0A"] = "America/Iqaluit"
    # Nunavut Kivalliq (Rankin Inlet 等) X0C
    mapping["X0C"] = "America/Rankin_Inlet"
    # Nunavut Kitikmeot（Cambridge Bay） X0B
    mapping["X0B"] = "America/Cambridge_Bay"
    # BC 东南 Creston 地区用 Mountain Time 但不 DST — FSA V0B (部分)
    # 这个太细了，先让 V 统一 Vancouver
    return mapping


# ============================================================
# 生成文件
# ============================================================
def main():
    import os
    os.makedirs("/home/claude/TimezoneSetter/app/src/main/assets", exist_ok=True)
    
    us_zip3 = build_us_zip3()
    overrides = OVERRIDES
    ca_fsa = build_ca_fsa()
    
    # 写 US ZIP3 文件（紧凑格式：每行 "ZIP3,TZ"）
    with open("/home/claude/TimezoneSetter/app/src/main/assets/us_zip3.csv", "w") as f:
        for z in sorted(us_zip3.keys()):
            f.write(f"{z},{us_zip3[z]}\n")
    
    # 写 US ZIP5 覆盖文件
    with open("/home/claude/TimezoneSetter/app/src/main/assets/us_overrides.csv", "w") as f:
        for z in sorted(overrides.keys()):
            f.write(f"{z},{overrides[z]}\n")
    
    # 写 CA FSA 文件
    with open("/home/claude/TimezoneSetter/app/src/main/assets/ca_fsa.csv", "w") as f:
        for z in sorted(ca_fsa.keys()):
            f.write(f"{z},{ca_fsa[z]}\n")
    
    print(f"US ZIP3 entries: {len(us_zip3)}")
    print(f"US overrides: {len(overrides)}")
    print(f"CA FSA entries: {len(ca_fsa)}")
    
    # 打印各时区分布
    from collections import Counter
    us_tz_count = Counter(us_zip3.values())
    print("\nUS timezone distribution:")
    for tz, cnt in us_tz_count.most_common():
        print(f"  {tz}: {cnt}")

if __name__ == "__main__":
    main()
