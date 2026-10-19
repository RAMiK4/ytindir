import json
import os
import re
import urllib.error
import urllib.parse
import urllib.request

from yt_dlp.postprocessor.common import PostProcessor

API = 'https://lrclib.net/api'
USER_AGENT = 'YTindir (https://github.com/RAMiK4/ytindir)'
DURATION_TOLERANCE = 5

_LRC_STAMP = re.compile(r'\[(\d+):(\d+(?:\.\d+)?)\]')
_JUNK_BRACKETS = re.compile(
    r'\s*[\(\[][^\)\]]*(?:official|video|audio|lyric|visuali[sz]er|\bhd\b|\b4k\b|\bmv\b|remaster)[^\)\]]*[\)\]]',
    re.IGNORECASE)


# YTindir: the yt-dlp instance running this postprocessor. Its own networking already
# knows where the certificates are (the same one that downloads the video), so the
# lyrics requests go through it instead of plain urllib.
_YDL = None


def _ssl_context():
    import ssl
    ctx = ssl.create_default_context()
    candidates = []
    try:
        import certifi
        candidates.append(certifi.where())
    except Exception:
        pass
    candidates += [os.environ.get('SSL_CERT_FILE'), '/system/etc/security/cacerts']
    for c in candidates:
        if not c or not os.path.exists(c):
            continue
        try:
            if os.path.isdir(c):
                ctx.load_verify_locations(capath=c)
            else:
                ctx.load_verify_locations(cafile=c)
        except Exception:
            pass
    return ctx


def _get_json(path, params=None):
    url = f'{API}/{path}'
    if params:
        url += '?' + urllib.parse.urlencode({k: v for k, v in params.items() if v not in (None, '')})
    headers = {'User-Agent': USER_AGENT}

    if _YDL is not None:
        try:
            from yt_dlp.networking import Request
            from yt_dlp.networking.exceptions import HTTPError as YdlHTTPError
            try:
                with _YDL.urlopen(Request(url, headers=headers)) as res:
                    return json.loads(res.read().decode('utf-8'))
            except YdlHTTPError:
                # a 400/404 on one attempt must not stop the other attempts
                return None
        except Exception:
            pass  # fall back to plain urllib below

    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=10, context=_ssl_context()) as res:
            return json.loads(res.read().decode('utf-8'))
    except urllib.error.HTTPError:
        return None


_FEAT_PART = re.compile(r'\s*[\(\[]\s*(?:feat|ft)\.?\s[^\)\]]*[\)\]]', re.IGNORECASE)
_FEAT_TAIL = re.compile(r'\s+(?:feat|ft)\.?\s.*$', re.IGNORECASE)


def clean_artist(artist):
    """'Shakira and 2 more' / 'Shakira, Burna Boy' / 'Artist - Topic' -> 'Shakira'"""
    a = re.sub(r'\s*-\s*Topic$', '', artist or '', flags=re.IGNORECASE)
    a = re.sub(r'\s+and\s+\d+\s+more$', '', a, flags=re.IGNORECASE)
    a = re.split(r'\s*(?:,|&|\bx\b|\bfeat\.?|\bft\.?|\bve\b|\band\b)\s*', a, flags=re.IGNORECASE)[0]
    return a.strip()


def clean_title(track):
    t = _JUNK_BRACKETS.sub('', track or '')
    t = _FEAT_PART.sub('', t)
    t = t.split('|')[0]
    t = _FEAT_TAIL.sub('', t)
    return t.strip().strip('- ').strip()


def parse_lrc(text):
    """Returns a sorted list of (milliseconds, line)."""
    lines = []
    for raw in (text or '').splitlines():
        stamps = list(_LRC_STAMP.finditer(raw))
        if not stamps:
            continue
        content = raw[stamps[-1].end():].strip()
        for s in stamps:
            ms = int((int(s.group(1)) * 60 + float(s.group(2))) * 1000)
            lines.append((ms, content))
    lines.sort(key=lambda l: l[0])
    return lines


def format_lrc(lines, offset=0):
    out = []
    for ms, content in lines:
        ms = max(0, ms + offset)
        out.append(f'[{ms // 60000:02d}:{(ms % 60000) / 1000:05.2f}]{content}')
    return '\n'.join(out)


def _pick(results, duration, tolerance=DURATION_TOLERANCE):
    best = None
    for r in results or []:
        if r.get('instrumental'):
            continue
        if not (r.get('syncedLyrics') or r.get('plainLyrics')):
            continue
        diff = abs((r.get('duration') or 0) - duration) if duration else 0
        if duration and diff > tolerance:
            continue
        # synced lyrics first, then closest duration
        key = (0 if r.get('syncedLyrics') else 1, diff)
        if best is None or key < best[0]:
            best = (key, r)
    return best[1] if best else None


def find_lyrics(track, artist, album, duration, lrclib_id=None):
    if lrclib_id:
        return _get_json(f'get/{lrclib_id}')

    channel_artist = clean_artist(artist)
    # "Artist | Title" uploads: keep the part that is not the artist
    parts = [p.strip() for p in (track or '').split('|') if p.strip()]
    if len(parts) > 1 and channel_artist and clean_artist(parts[0]).lower() == channel_artist.lower():
        track = ' | '.join(parts[1:])
    title = clean_title(track)
    title_artist = ''
    if ' - ' in title:
        left, right = title.split(' - ', 1)
        title_artist = clean_artist(left)
        title = clean_title(right)
    artists = [a for i, a in enumerate([title_artist, channel_artist]) if a and a not in [title_artist, channel_artist][:i]]

    if artists:
        res = _get_json('get', {
            'track_name': title, 'artist_name': artists[0],
            'album_name': album, 'duration': duration or None})
        if res and (res.get('syncedLyrics') or res.get('plainLyrics')):
            return res

    searches = [{'track_name': title, 'artist_name': a} for a in artists] + \
               [{'q': f'{a} {title}'} for a in artists] + [{'q': title}]
    responses = []
    for params in searches:
        results = _get_json('search', params)
        res = _pick(results, duration)
        if res:
            return res
        responses.append(results)
    # video uploads are often longer than the song (intro, outro), so allow more slack
    for results in responses:
        res = _pick(results, duration, 45)
        if res:
            return res
    return None


def _syncsafe(n):
    return bytes([(n >> 21) & 0x7F, (n >> 14) & 0x7F, (n >> 7) & 0x7F, n & 0x7F])


def _unsyncsafe(b):
    return (b[0] << 21) | (b[1] << 14) | (b[2] << 7) | b[3]


def _id3_add_uslt(path, text):
    """YTindir: writes an ID3 USLT (lyrics) frame without mutagen."""
    with open(path, 'rb') as f:
        data = f.read()
    if data[:3] == b'ID3':
        ver, flags = data[3], data[5]
        if flags & 0xC0:  # unsynchronisation / extended header: leave such files alone
            raise ValueError('unsupported ID3 header')
        size = _unsyncsafe(data[6:10])
        tag, audio = data[10:10 + size], data[10 + size:]
    else:
        ver, flags, tag, audio = 4, 0, b'', data
    if ver >= 4:
        body = b'\x03eng\x00' + text.encode('utf-8')
        fsize = _syncsafe(len(body))
    else:
        body = b'\x01eng\xff\xfe\x00\x00' + text.encode('utf-16-le')
        fsize = len(body).to_bytes(4, 'big')
    frame = b'USLT' + fsize + b'\x00\x00' + body
    new_tag = frame + tag
    header = b'ID3' + bytes([ver, 0, flags]) + _syncsafe(len(new_tag))
    tmp = path + '.lyrics.tmp'
    with open(tmp, 'wb') as f:
        f.write(header + new_tag + audio)
    os.replace(tmp, path)


def _ffmpeg_lyrics(path, text, key):
    """YTindir: fallback for m4a/opus/flac when mutagen is not available."""
    import subprocess
    ext = os.path.splitext(path)[1]
    tmp = path + '.lyrics' + ext
    res = subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', '-i', path, '-map', '0', '-c', 'copy',
                          '-map_metadata', '0', '-metadata', f'{key}={text}', tmp],
                         stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if res.returncode != 0 or not os.path.exists(tmp):
        raise ValueError(res.stderr.decode('utf-8', 'replace').strip() or 'ffmpeg failed')
    os.replace(tmp, path)


def embed(path, text, synced):
    try:
        _embed_mutagen(path, text, synced)
    except ImportError:
        ext = os.path.splitext(path)[1].lower().lstrip('.')
        if ext == 'mp3':
            _id3_add_uslt(path, text)
        elif ext in ('m4a', 'mp4'):
            _ffmpeg_lyrics(path, text, 'lyrics')
        elif ext in ('opus', 'ogg', 'oga', 'flac'):
            _ffmpeg_lyrics(path, text, 'LYRICS')
        else:
            raise


def _embed_mutagen(path, text, synced):
    ext = os.path.splitext(path)[1].lower().lstrip('.')

    if ext == 'mp3':
        from mutagen.id3 import ID3, ID3NoHeaderError, SYLT, USLT
        try:
            tags = ID3(path)
        except ID3NoHeaderError:
            tags = ID3()
        tags.delall('USLT')
        tags.delall('SYLT')
        tags.add(USLT(encoding=3, lang='eng', desc='', text=text))
        if synced:
            tags.add(SYLT(encoding=3, lang='eng', format=2, type=1, desc='',
                          text=[(content, ms) for ms, content in synced]))
        tags.save(path)
    elif ext in ('m4a', 'mp4'):
        from mutagen.mp4 import MP4
        audio = MP4(path)
        if audio.tags is None:
            audio.add_tags()
        audio.tags['\xa9lyr'] = [text]
        audio.save()
    elif ext in ('opus', 'ogg', 'oga', 'flac'):
        import mutagen
        audio = mutagen.File(path)
        if audio is None:
            raise ValueError('unsupported file')
        audio['lyrics'] = [text]
        audio.save()
    else:
        raise ValueError(f'embedding lyrics is not supported for .{ext} files')


class LyricsPP(PostProcessor):
    """Fetches lyrics from lrclib.net and embeds them into the audio file.

    Must run with when=after_move: post_process plugins run before audio
    extraction, so the file would still be the original webm/m4a stream.

    Args (all optional):
      id       lrclib track id, skips matching when set
      offset   milliseconds added to every synced timestamp
      embed    1/0 write the lyrics into the file tags
      lrc      1/0 save a .lrc file next to the audio file when synced lyrics exist
    """

    def __init__(self, downloader=None, id=None, offset=0, embed=1, lrc=0, **kwargs):
        super().__init__(downloader)
        self._id = int(id) if id and str(id).isdigit() else None
        self._offset = int(offset or 0)
        self._embed = str(embed) != '0'
        self._lrc = str(lrc) == '1'

    @staticmethod
    def _artist(info):
        artist = info.get('artist') or ''
        if not artist and info.get('artists'):
            artist = info['artists'][0]
        artist = artist or info.get('uploader') or info.get('channel') or ''
        artist = re.sub(r'\s*-\s*Topic$', '', artist)
        return artist.split(',')[0].strip()

    def run(self, info):
        path = info.get('filepath')
        if not path or not os.path.exists(path):
            return [], info

        global _YDL
        _YDL = self._downloader
        track = info.get('track') or info.get('title') or ''
        try:
            result = find_lyrics(track, self._artist(info), info.get('album') or '',
                                 int(info.get('duration') or 0), self._id)
        except Exception as e:
            self.report_warning(f'Could not fetch lyrics: {e}')
            return [], info

        if not result or result.get('instrumental'):
            self.to_screen('No lyrics found')
            return [], info

        synced = parse_lrc(result.get('syncedLyrics'))
        shifted = [(max(0, ms + self._offset), c) for ms, c in synced]
        plain = result.get('plainLyrics') or '\n'.join(c for _, c in synced)
        # Only mp3 has a real synced tag (SYLT). Everywhere else players expect
        # LRC text, with timestamps, inside the regular lyrics tag.
        text = format_lrc(synced, self._offset) if synced else plain

        if self._embed:
            try:
                embed(path, text, shifted)
                self.to_screen('Embedded lyrics' + (' (synced, LRC)' if shifted else ' (plain)'))
            except Exception as e:
                self.report_warning(f'Could not embed lyrics: {e}')

        if self._lrc and shifted:
            lrc_path = os.path.splitext(path)[0] + '.lrc'
            try:
                with open(lrc_path, 'w', encoding='utf-8') as f:
                    f.write(format_lrc(synced, self._offset))
                self.to_screen(f'Saved {os.path.basename(lrc_path)}')
            except OSError as e:
                self.report_warning(f'Could not save lyrics file: {e}')

        return [], info
