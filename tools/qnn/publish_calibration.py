#!/usr/bin/env python3
"""Upload only encrypted calibration assets and their public catalog to GitHub.

Creates a draft by default. --publish makes it a public, non-latest prerelease.
Authentication uses GH_TOKEN or Git Credential Manager; credentials are never logged.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import urllib.error
import urllib.parse
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--repo", default="HaoweiLi97/mihon_img_upscale")
    parser.add_argument("--publish", action="store_true")
    args = parser.parse_args()
    token = os.environ.get("GH_TOKEN")
    if not token:
        result = subprocess.run(
            ["git", "credential", "fill"], input="protocol=https\nhost=github.com\n\n",
            text=True, capture_output=True, check=True,
            env={**os.environ, "GCM_INTERACTIVE": "never", "GIT_TERMINAL_PROMPT": "0"},
        )
        fields = dict(line.split("=", 1) for line in result.stdout.splitlines() if "=" in line)
        token = fields["password"]

    def api(method, path, body=None, data=None):
        url = path if path.startswith("https://uploads.github.com/") else f"https://api.github.com/repos/{args.repo}{path}"
        request = urllib.request.Request(url, method=method, headers={
            "Authorization": "Bearer " + token, "Accept": "application/vnd.github+json",
            "User-Agent": "mihon-calibration-release", "X-GitHub-Api-Version": "2022-11-28",
            "Content-Type": "application/octet-stream" if data is not None else "application/json",
        }, data=data if data is not None else json.dumps(body).encode() if body is not None else None)
        with urllib.request.urlopen(request, timeout=180) as response:
            return json.load(response)

    catalog = json.loads((args.directory / "catalog.json").read_text(encoding="utf-8"))
    assets = [args.directory / "catalog.json"]
    for pack in catalog["packs"].values():
        path = args.directory / pack["file"]
        if path.parent.resolve() != args.directory.resolve() or path.suffix != ".aes":
            raise ValueError("Only the catalog's encrypted assets may be uploaded")
        data = path.read_bytes()
        if not data.startswith(b"MIHQCAL1") or len(data) != pack["bytes"] or hashlib.sha256(data).hexdigest() != pack["sha256"]:
            raise ValueError("Encrypted release asset does not match the catalog")
        assets.append(path)
    tag = "npu-calibration-" + catalog["version"]
    body = (
        "按需下载的加密校准集，供支持本机校准功能的应用使用。校准、W8A16 量化和当前设备的 HTP 编译均在用户设备执行。\n\n"
        "- 漫画：160 张校准裁块及 8 张独立验证裁块。\n"
        "- 照片：200 张校准裁块及 8 张独立验证裁块。\n"
        "- 发布文件采用 AES-256-GCM 加密，不包含预量化模型、预编译 context 或解密密钥。\n"
        "- 校准数据采用 256×256 RGB/NHWC；应用验证认证标签与逐文件 SHA-256。\n\n"
        "漫画数据来源：[OpenMantra](https://github.com/mantra-inc/open-mantra-dataset)，"
        "遵循 CC BY-NC 4.0，仅供非商业用途；原作者署名、许可文本和裁切/缩放说明保留在包内。\n\n"
        "照片来源：[BSDS500 / UC Berkeley](https://www2.eecs.berkeley.edu/Research/Projects/CS/vision/grouping/resources.html)，"
        "原图片权利及使用条件保留；包内提供来源和论文引用。\n\n"
        "此 Release 仅分发数据资源，不替代真机兼容性验证，也不作为应用更新推送。"
    )
    try:
        release = api("GET", "/releases/tags/" + urllib.parse.quote(tag, safe=""))
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise
        release = next((item for item in api("GET", "/releases?per_page=100") if item["tag_name"] == tag), None)
        if release is None:
            target = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
            release = api("POST", "/releases", {"tag_name": tag, "target_commitish": target,
                "name": "NPU 本机校准集 " + catalog["version"], "body": body,
                "draft": True, "prerelease": True, "make_latest": "false"})
    for path in assets:
        existing = next((a for a in release["assets"] if a["name"] == path.name), None)
        digest = "sha256:" + hashlib.sha256(path.read_bytes()).hexdigest()
        if existing:
            if existing.get("digest") != digest:
                raise ValueError("An existing release asset has different contents; create a new version")
            print("Already uploaded:", path.name)
            continue
        endpoint = release["upload_url"].split("{", 1)[0] + "?" + urllib.parse.urlencode({"name": path.name})
        uploaded = api("POST", endpoint, data=path.read_bytes())
        if uploaded.get("digest") != digest:
            raise ValueError("GitHub asset digest does not match the local file")
        print("Uploaded:", path.name, uploaded["size"])
    if args.publish:
        release = api("PATCH", f"/releases/{release['id']}", {"draft": False, "prerelease": True, "make_latest": "false", "body": body})
    print(json.dumps({"url": release["html_url"], "draft": release["draft"], "id": release["id"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
