#!/usr/bin/env python3
# -*- coding: utf-8 -*-

# Google API Key: set via environment variable GOOGLE_API_KEY
# Example: export GOOGLE_API_KEY="your_api_key_here"

import os
import re
import sys
import json
import logging
import time
from pathlib import Path
from typing import Dict, Optional, List
from urllib.parse import urlparse

try:
    from Kekik.cli import konsol
except ImportError:
    import logging
    logging.basicConfig(level=logging.INFO)
    class SimpleConsole:
        def log(self, msg): print(msg)
    konsol = SimpleConsole()

try:
    from cloudscraper import CloudScraper
except ImportError:
    import requests
    CloudScraper = requests.Session

class MainUrlUpdater:
    """CloudStream eklentileri için URL ve versiyon güncelleyicisi"""
    
    EXCLUDED_DIRS = {"gradle", "CanliTV", "OxAx", "__Temel", "SineWix", "YouTube", 
                     "NetflixMirror", "HQPorner", ".git", ".github", "node_modules"}
    
    def __init__(self, base_dir="."):
        self.base_dir = base_dir
        self.session = CloudScraper()
        self.session.headers.update({
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        })
        self.updated_count = 0
        self.error_count = 0
        self.unchanged_count = 0
        self.retry_count = 3
        self.timeout = 10
        self.change_log: List[Dict[str, str]] = []
        self.domain_mappings = self._load_domain_mappings()

    def _load_domain_mappings(self) -> Dict[str, str]:
        """domains.json dosyasından domain eşlemelerini yükle"""
        mapping_file = os.path.join(self.base_dir, "domains.json")
        if not os.path.exists(mapping_file):
            return {}
        try:
            with open(mapping_file, "r", encoding="utf-8") as file:
                payload = json.load(file)
            mappings = payload.get("mappings", {}) if isinstance(payload, dict) else {}
            return {
                self._normalize_domain(old): new
                for old, new in mappings.items()
                if isinstance(new, str) and new.strip()
            }
        except Exception as e:
            konsol.log(f"[!] domains.json okuma hatası: {e}")
            return {}

    @staticmethod
    def _normalize_domain(url_or_domain: str) -> str:
        raw = (url_or_domain or "").strip().lower()
        if not raw:
            return ""
        if "://" in raw:
            raw = urlparse(raw).netloc
        raw = raw.split("/")[0]
        if raw.startswith("www."):
            raw = raw[4:]
        return raw

    @property
    def plugins(self) -> List[str]:
        """Geçerli eklentileri döndür"""
        try:
            return sorted([
                dosya for dosya in os.listdir(self.base_dir)
                    if os.path.isdir(os.path.join(self.base_dir, dosya))
                        and not dosya.startswith(".")
                        and dosya not in self.EXCLUDED_DIRS
            ])
        except Exception as e:
            konsol.log(f"[!] Eklentiler okunurkken hata: {e}")
            return []

    def _find_kt_file(self, directory: str, filename: str) -> Optional[str]:
        """Kotlin dosyasını bul"""
        try:
            for root, dirs, files in os.walk(directory):
                if filename in files:
                    return os.path.join(root, filename)
        except Exception as e:
            konsol.log(f"[!] Dosya araması sırasında hata: {e}")
        return None

    @property
    def kotlin_files(self) -> List[str]:
        """Tüm Kotlin dosyalarını döndür"""
        files = []
        for plugin in self.plugins:
            kt_file = self._find_kt_file(plugin, f"{plugin}.kt")
            if kt_file:
                files.append(kt_file)
        return files

    def _find_mainurl(self, kt_file_path: str) -> Optional[str]:
        """mainUrl değerini bul"""
        try:
            with open(kt_file_path, "r", encoding="utf-8") as file:
                content = file.read()
                match = re.search(r'override\s+var\s+mainUrl\s*=\s*"([^"]+)"', content)
                if match:
                    return match.group(1)
        except Exception as e:
            konsol.log(f"[!] mainUrl okuma hatası ({kt_file_path}): {e}")
        return None

    def _update_mainurl(self, kt_file_path: str, old_url: str, new_url: str) -> bool:
        """mainUrl değerini güncelle"""
        try:
            with open(kt_file_path, "r", encoding="utf-8") as file:
                content = file.read()
            
            new_content = content.replace(old_url, new_url)
            
            if new_content != content:
                with open(kt_file_path, "w", encoding="utf-8") as file:
                    file.write(new_content)
                return True
        except Exception as e:
            konsol.log(f"[!] mainUrl yazma hatası ({kt_file_path}): {e}")
        return False

    def _increment_version(self, build_gradle_path: str) -> Optional[int]:
        """build.gradle.kts dosyasında versiyon artır"""
        try:
            with open(build_gradle_path, "r", encoding="utf-8") as file:
                content = file.read()
            
            match = re.search(r'version\s*=\s*(\d+)', content)
            if match:
                old_version = int(match.group(1))
                new_version = old_version + 1
                new_content = content.replace(
                    f"version = {old_version}", 
                    f"version = {new_version}"
                )
                
                with open(build_gradle_path, "w", encoding="utf-8") as file:
                    file.write(new_content)
                return new_version
        except Exception as e:
            konsol.log(f"[!] Versiyon güncelleme hatası ({build_gradle_path}): {e}")
        return None

    def _rectv_version(self) -> Optional[str]:
        """RecTV URL'sini getir"""
        try:
            response = self.session.post(
                url="https://firebaseremoteconfig.googleapis.com/v1/projects/791583031279/namespaces/firebase:fetch",
                headers={
                    "X-Goog-Api-Key": os.getenv("GOOGLE_API_KEY"),
                    "X-Android-Package": "com.rectv.shot",
                    "User-Agent": "Dalvik/2.1.0 (Linux; U; Android 12)",
                },
                json={
                    "appBuild": "81",
                    "appInstanceId": "evON8ZdeSr-0wUYxf0qs68",
                    "appId": "1:791583031279:android:1",
                },
                timeout=10
            )
            data = response.json()
            url = data.get("entries", {}).get("api_url", "").replace("/api/", "")
            return url if url else None
        except Exception as e:
            konsol.log(f"[!] RecTV sürümü alınamadı: {e}")
            return None

    def _verify_url(self, url: str) -> Optional[str]:
        """URL'i doğrula ve son URL'yi döndür"""
        for attempt in range(1, self.retry_count + 1):
            for method in ("head", "get"):
                try:
                    response = self.session.request(
                        method=method,
                        url=url,
                        allow_redirects=True,
                        timeout=self.timeout
                    )
                    if response.status_code < 400:
                        return response.url.rstrip("/")
                except Exception:
                    continue
            if attempt < self.retry_count:
                time.sleep(min(attempt, 3))
        return None

    def _find_replacement_url(self, current_url: str) -> Optional[str]:
        """Bozuk domain için mapping veya sezgisel adaylardan yeni URL bul"""
        current_domain = self._normalize_domain(current_url)
        mapping_target = self.domain_mappings.get(current_domain)
        if mapping_target:
            mapped_final = self._verify_url(mapping_target)
            if mapped_final:
                return mapped_final

        base = re.sub(r"\d+$", "", current_domain.split(".")[0])
        if not base:
            return None

        tlds = ["com", "net", "org", "live", "tv", "co", "cc", "de", "io", "xyz", "site"]
        for tld in tlds:
            for prefix in ("", "www."):
                candidate = f"https://{prefix}{base}.{tld}"
                candidate_final = self._verify_url(candidate)
                if candidate_final:
                    return candidate_final
        return None

    def _update_icon_url(self, build_gradle_path: str, old_url: str, new_url: str) -> bool:
        """build.gradle.kts içindeki iconUrl domain parametresini güncelle"""
        try:
            with open(build_gradle_path, "r", encoding="utf-8") as file:
                content = file.read()

            old_domain = self._normalize_domain(old_url)
            new_host = urlparse(new_url).netloc or self._normalize_domain(new_url)
            if not old_domain or not new_host:
                return False

            new_content = re.sub(
                r'(iconUrl\s*=\s*"https://www\.google\.com/s2/favicons\?domain=)([^"&]+)(&sz=%size%")',
                rf"\1{new_host}\3",
                content,
                count=1
            )
            if new_content == content:
                new_content = re.sub(
                    r'(iconUrl\s*=\s*"https?://)([^/"]+)([^"]*")',
                    rf"\1{new_host}\3",
                    content,
                    count=1
                )

            if new_content != content:
                with open(build_gradle_path, "w", encoding="utf-8") as file:
                    file.write(new_content)
                return True
        except Exception as e:
            konsol.log(f"[!] iconUrl güncelleme hatası ({build_gradle_path}): {e}")
        return False

    @property
    def mainurl_list(self) -> Dict[str, Optional[str]]:
        """mainUrl listesini döndür"""
        return {
            file_path: self._find_mainurl(file_path) 
            for file_path in self.kotlin_files
        }

    def update(self):
        """Tüm eklentileri güncelle"""
        if not self.plugins:
            konsol.log("[!] Güncellenecek eklenti bulunamadı")
            return

        konsol.log(f"\n[*] {len(self.plugins)} eklenti kontrol ediliyor...\n")

        for kt_file, mainurl in self.mainurl_list.items():
            plugin_name = Path(kt_file).parts[0]
            
            if not mainurl:
                konsol.log(f"[!] {plugin_name}: mainUrl bulunamadı")
                self.error_count += 1
                continue

            konsol.log(f"[~] {plugin_name}: Kontrol ediliyor ({mainurl})")

            try:
                if plugin_name == "RecTV":
                    final_url = self._rectv_version()
                    if not final_url:
                        konsol.log(f"[!] {plugin_name}: RecTV URL alınamadı")
                        self.error_count += 1
                        continue
                else:
                    final_url = self._verify_url(mainurl)
                    if not final_url:
                        mapped_url = self._find_replacement_url(mainurl)
                        if mapped_url:
                            konsol.log(f"[~] {plugin_name}: Eşleme ile yeni domain bulundu ({mapped_url})")
                            final_url = mapped_url
                        else:
                            konsol.log(f"[!] {plugin_name}: URL doğrulanamadı")
                            self.error_count += 1
                            continue

                if mainurl == final_url:
                    konsol.log(f"[✓] {plugin_name}: Güncel")
                    self.unchanged_count += 1
                    continue

                # URL güncelle
                if self._update_mainurl(kt_file, mainurl, final_url):
                    # Versiyon artır
                    build_gradle = f"{plugin_name}/build.gradle.kts"
                    if os.path.exists(build_gradle):
                        self._update_icon_url(build_gradle, mainurl, final_url)
                        new_version = self._increment_version(build_gradle)
                        if new_version:
                            konsol.log(f"[✓] {plugin_name}: v{new_version} - {mainurl} → {final_url}")
                            self.updated_count += 1
                            self.change_log.append({
                                "plugin": plugin_name,
                                "old_mainUrl": mainurl,
                                "new_mainUrl": final_url,
                                "old_domain": self._normalize_domain(mainurl),
                                "new_domain": self._normalize_domain(final_url)
                            })
                        else:
                            konsol.log(f"[!] {plugin_name}: Versiyon güncellenemedi")
                            self.error_count += 1
                    else:
                        konsol.log(f"[!] {plugin_name}: build.gradle.kts bulunamadı")
                        self.error_count += 1
                else:
                    konsol.log(f"[!] {plugin_name}: URL güncellenemedi")
                    self.error_count += 1

            except Exception as e:
                konsol.log(f"[!] {plugin_name}: İşlem hatası - {e}")
                self.error_count += 1

        # Özet
        konsol.log(f"\n{'='*60}")
        konsol.log(f"[*] Güncelleme Özeti:")
        konsol.log(f"    ✓ Güncellenen: {self.updated_count}")
        konsol.log(f"    ✓ Güncel: {self.unchanged_count}")
        konsol.log(f"    ✗ Hata: {self.error_count}")
        konsol.log(f"{'='*60}\n")

        if self.change_log:
            try:
                log_file = os.path.join(self.base_dir, "domain_update_log.json")
                with open(log_file, "w", encoding="utf-8") as file:
                    json.dump(self.change_log, file, ensure_ascii=False, indent=2)
                konsol.log(f"[*] {log_file} dosyasına değişiklikler yazıldı")
            except Exception as e:
                konsol.log(f"[!] Değişiklik logu yazılamadı: {e}")

        return self.updated_count > 0


if __name__ == "__main__":
    updater = MainUrlUpdater()
    success = updater.update()
    sys.exit(0 if success else 1)
