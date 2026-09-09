#!/usr/bin/env python3
import os
import json
import sys
import struct


# 用于记录每个事件的所有数据信息，支持直接+ - * /
class EventInfo:
    value = []
    name = ""
    def __init__(self, name):
        self.name = name
        self.value = []

    # 记录值
    def addinfo(self, value):
        self.value.append(value)

    # 根据eventname判断两个事件是否相同
    def isThis(self, name):
        return (name == self.name)

    # 根据optype完成各种EventInfo的value的操作，要求value的长度相同才行
    def op(self, other, optype):
        temp = EventInfo("none")
        idx = 0
        if isinstance(other, float) or isinstance(other, int):
            temp.name = "(" + self.name + optype + str(other) + ")"
            while idx < len(self.value):
                if optype == "-":
                    val = self.value[idx] - other
                elif optype == "*":
                    val = self.value[idx] * other
                elif optype == "/":
                    val = 1.0 * self.value[idx] / other
                else:
                    val = self.value[idx] + other
                temp.addinfo(val)
                idx = idx + 1

        if isinstance(other, EventInfo):
            temp.name = "(" + self.name + optype + other.name + ")"
            if len(other.value) != len(self.value):
                return temp
            while idx < len(self.value):
                if optype == "-":
                    val = self.value[idx] - other.value[idx]
                elif optype == "*":
                    val = self.value[idx] * other.value[idx]
                elif optype == "/":
                    val = 1.0 * self.value[idx] / other.value[idx] if other.value[idx] else float("nan")
                else:
                    val = self.value[idx] + other.value[idx]
                temp.addinfo(val)
                idx = idx + 1
        return temp

    # 重载加减乘除运算
    def __add__(self, other):
        return self.op(other, "+")

    def __sub__(self, other):
        return self.op(other, "-")

    def __mul__(self, other):
        return self.op(other, "*")

    def __truediv__(self, other):
        return self.op(other, "/")


# ----------------------------------------------------------------------------------

# 读取所有事件的名称
def read_eventlist():
    if not os.path.exists("eventlist") :
        print("the file eventlist is not in this directory!")
        return 0

    file1 = open("eventlist") 
    eventdict = {}
    while 1:
        line = file1.readline()
        if not line:
            break
        words = line.replace(" ", "").replace("\n", "").split(',')
        eventdict[words[0]] = words[1]
        # print(words)
    file1.close()
    return eventdict


# einfos为一组事件，计算einfos中每一个事件占einfos所有事件数量的比例
def cal_percentage(einfos):
    totalinfo = einfos[0]
    idx = 1
    while idx < len(einfos):
        totalinfo = totalinfo + einfos[idx]
        idx = idx + 1

    res = []
    idx = 0
    while idx < len(einfos):
        info = einfos[idx] / totalinfo
        info.name = einfos[idx].name + "_perc"
        res.append(info)
        idx = idx + 1
    return res

# 计算比例
def cal_fraction(numerator, denominator, name):
    temp = numerator / denominator
    if len(name) != 0:
        temp.name = name
    return temp


# -----------------------------------------------------------------
# 将startinfo和eventinfos中的信息保存成csv文件，用于excel查看，optype分为h和v，为两种不同的存储方式
def saveEventInfo(eventinfos, benchname):
    bench = os.path.splitext(benchname)[0]
    savename = bench+"_eventinfo"+".csv"
    print(savename)
    f1 = open(savename, 'w')

    data = []
    str1 = ","
    idx = 0
    while idx < len(eventinfos[0].value):
        str1 = str1 + str(idx+1) + ","
        idx = idx + 1
    data.append(str1)

    for info in eventinfos:
        str1 = info.name + ","
        idx = 0
        while idx < len(info.value):
            str1 = str1 + str(info.value[idx])
            str1 = str1 + ","
            idx = idx + 1  
        data.append(str1)
        
    
    for d in data:
        f1.write(d + "\n")


# 从运行的log文件中获取startinfo和event事件信息
# 要求：文件由多组事件组成，每一组事件的格式为：
# {"type": "max_inst", "cycles": 533340278, "inst": 221043002} #起始
# {"type": "event  0", "value":  467700375} #具体事件
# {"type": "event  1", "value":  200005587}
def readEventInfo(filename, eventdict):
    file1 = open(filename) 
    eventinfos = []
    numline = 0
    while 1:
        line = file1.readline()
        numline += 1
        if not line:
            break
        if line.find("{") == -1 and line.find("}") == -1:
            continue

        if line.find("{") == -1 or line.find("}") == -1:
            print(numline, line)
            continue

        if line.find("type") == -1 or line.find("event") == -1:
            continue

        line = "{" + line.split("{")[1].split("}")[0] + "}"

        info = json.loads(line)
        
        typeinfo = info['type']
        idx = 0
        while idx < len(eventinfos):
            if eventinfos[idx].isThis(typeinfo):
                break
            idx = idx + 1
        if idx == len(eventinfos):
            einfo = EventInfo(typeinfo)
            einfo.addinfo(info['value'])
            eventinfos.append(einfo)
        else:
            einfo = eventinfos[idx]
            einfo.addinfo(info['value'])

    file1.close()

    for info in eventinfos:
        name = info.name.replace(" ", "")
        if name in eventdict:
            info.name = eventdict[name]

    return eventinfos


def process_infos(eventinfos):
    # Resolve names instead of positions: the compact predictor map differs
    # from the original cache/dispatch map and logs may contain fewer events.
    by_name = {info.name: info for info in eventinfos}

    def ratio(numerator, denominator, name):
        if numerator in by_name and denominator in by_name:
            eventinfos.append(cal_fraction(by_name[numerator], by_name[denominator], name))

    ratio("insts", "cycles", "user_ipc")
    for outcome in ("success", "fail"):
        ratio("tage_alloc_" + outcome, "tage_alloc_attempt", "tage_alloc_" + outcome + "_rate")
    for table in range(6):
        name = "tage_alloc_t" + str(table)
        ratio(name, "tage_alloc_success", name + "_perc")
    for provider in ["no_provider"] + ["provider_t" + str(i) for i in range(5)]:
        failed = "tage_alloc_fail_" + provider
        ratio(failed, "tage_alloc_fail", failed + "_per_fail")
    if all(name in by_name for name in ("exe_misp_br", "exe_misp_jalr", "insts")):
        eventinfos.append(cal_fraction(
            (by_name["exe_misp_br"] + by_name["exe_misp_jalr"]) * 1000,
            by_name["insts"], "exe_misp_MPKI"))
    return eventinfos


if len(sys.argv) < 2: 
    print("parameters are not enough.\n ./process.py logfile")
    exit()

if not os.path.exists(sys.argv[1]):
    print(sys.argv[1]+" is not exist.")
    exit()

# 读取事件名称
eventdict = read_eventlist()
# 从log文件中获取信息
eventinfos = readEventInfo(sys.argv[1], eventdict)

eventinfos = process_infos(eventinfos)
# 保存event信息到csv文件中
saveEventInfo(eventinfos, sys.argv[1])
