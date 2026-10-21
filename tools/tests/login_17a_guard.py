"""LoginViewModel 内部调用链守卫；按协议推进行为找闸，不依赖闸的名字。

只解析本文件普通命名函数的块体/表达式体，追踪直接调用、尾随 lambda、this 调用和函数引用。
不是 Kotlin 全程序分析：跨对象注入的回调/反射接线仍需 P02 人工评审。
"""
import re


def mask_non_code(source):
    """屏蔽注释与字面量但保留位置/换行，括号配对不受它们影响；支持嵌套块注释。"""
    result = list(source)
    token = re.compile(r'//|/\*|"""|"|\'')
    cursor = 0
    while match := token.search(source, cursor):
        start = match.start()
        kind = match.group()
        cursor = match.end()
        if kind == "//":
            end = source.find("\n", cursor)
            cursor = len(source) if end < 0 else end
        elif kind == "/*":
            depth = 1
            while depth:
                delimiter = re.search(r'/\*|\*/', source[cursor:])
                if delimiter is None:
                    raise AssertionError("未闭合 Kotlin 块注释")
                depth += 1 if delimiter.group() == "/*" else -1
                cursor += delimiter.end()
        elif kind == '"""':
            end = source.find(kind, cursor)
            if end < 0:
                raise AssertionError("未闭合 Kotlin 原始字符串")
            cursor = end + 3
        else:
            while cursor < len(source):
                char = source[cursor]
                cursor += 1
                if char == "\\":
                    cursor += 1
                elif char == kind:
                    break
            else:
                raise AssertionError("未闭合 Kotlin 字面量")
        for index in range(start, cursor):
            if result[index] != "\n":
                result[index] = " "
    return "".join(result)


def closing_bracket(source, start):
    stack = []
    pairs = {"(": ")", "[": "]", "{": "}"}
    for index in range(start, len(source)):
        char = source[index]
        if char in pairs:
            stack.append(pairs[char])
        elif char in pairs.values():
            if not stack or stack.pop() != char:
                raise AssertionError("Kotlin 括号不配对")
            if not stack:
                return index
    raise AssertionError("Kotlin 函数体未闭合")


def function_bodies(source):
    code = mask_non_code(source)
    bodies = {}
    for match in re.finditer(r'\bfun\s+(\w+)\s*\(', code):
        name = match.group(1)
        start = closing_bracket(code, match.end() - 1) + 1
        # 可选返回类型；遇下一个声明/接口的 } 就停止，不把无函数体的声明当成下一函数。
        header = re.match(r'\s*(?::[^\n={}]+)?\s*([={])', code[start:])
        if header is None:
            continue
        start += header.end() - 1
        if code[start] == "{":
            end = closing_bracket(code, start)
            body = code[start + 1:end]
        else:
            start += 1
            while start < len(code) and code[start].isspace():
                start += 1
            end = start
            while end < len(code) and code[end] not in "\n;}":
                if code[end] in "([{":
                    end = closing_bracket(code, end)
                end += 1
            body = code[start:end]
        if name in bodies:
            raise AssertionError(f"守卫需适配重载函数 {name}，不能跳过分析")
        bodies[name] = body
    return bodies


def agreement_gate_paths(source):
    bodies = function_bodies(source)
    if "onResend" not in bodies:
        raise AssertionError("缺少可分析的生产 onResend 入口")
    # tapLogin 推进状态机；consumeProceed 取走许可；直接写确认状态也是过闸。
    behavior = re.compile(
        r'\b(?:tapLogin|consumeProceed)\s*\('
        r'|\b(?:showConfirm|proceed)\s*=(?!=)'
        r'|\bdialog\s*=\s*(?:LoginDialog\s*\.\s*)?AGREE_CONFIRM\b'
        r'|\.\s*gate\s*\('
    )
    gates = {name for name, body in bodies.items() if behavior.search(body)}
    if not gates:
        raise AssertionError("没有识别到协议推进函数，需更新行为识别，不能空集放行")
    paths = []
    pending = [("onResend",)]
    seen = set()
    while pending:
        path = pending.pop()
        name = path[-1]
        if name in seen:
            continue
        seen.add(name)
        if name in gates:
            paths.append(path)
        for target in sorted(bodies):
            reference = re.compile(
                rf'(?<![\w.])(?:this\s*\.\s*)?{re.escape(target)}\s*(?:\(|\{{)'
                rf'|::\s*{re.escape(target)}\b'
            )
            if reference.search(bodies[name]):
                pending.append((*path, target))
    return sorted(paths)


def assert_resend_bypasses_agreement(source):
    paths = agreement_gate_paths(source)
    if paths:
        raise AssertionError("重新获取调用链经过协议闸：" + "; ".join(" -> ".join(path) for path in paths))
