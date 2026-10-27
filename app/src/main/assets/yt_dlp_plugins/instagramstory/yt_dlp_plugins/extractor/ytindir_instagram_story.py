"""
YTindir: Instagram stories and highlights through Instagram's own APIs, the way the Instagram app
(and Instagram Piko) reads them.

yt-dlp finds the account's id in the story web page, which Instagram no longer fills in, so it
answers "You need to log in" even with a logged-in account in the cookies. Here a story link is
read straight from its media info, and a whole story or highlight from the reels_media API with the
account id taken from the profile API. Each call is tried on the website's API (web app id) and on
the app's API (iOS app id and user agent). When every way fails, yt-dlp's own story extractor runs
as before, so a fix in yt-dlp keeps working with this plugin in place.
"""

from yt_dlp.extractor.instagram import InstagramStoryIE, _pk_to_id
from yt_dlp.networking.exceptions import HTTPError
from yt_dlp.utils import ExtractorError, filter_dict, str_or_none, traverse_obj


class YTindirInstagramStoryIE(InstagramStoryIE, plugin_name='ytindir'):
    _WEB_API = 'https://www.instagram.com/api/v1'
    _APP_API = 'https://i.instagram.com/api/v1'
    _IOS_APP_ID = '124024574287414'
    _WEB_APP_ID = '936619743392459'
    _IOS_USER_AGENT = (
        'Instagram 361.0.0.35.82 (iPhone14,5; iOS 17_6; en_US; en; '
        'scale=3.00; 1170x2532; 674117118)')

    def _share_cookies(self):
        # cookies saved by a browser can belong to www.instagram.com only: the app API on
        # i.instagram.com needs them too
        for name, morsel in self._get_cookies('https://www.instagram.com/').items():
            self._set_cookie('.instagram.com', name, morsel.value)

    def _headers(self, app):
        if app:
            return {'User-Agent': self._IOS_USER_AGENT, 'X-IG-App-ID': self._IOS_APP_ID, 'Accept': '*/*'}
        csrf = self._get_cookies('https://www.instagram.com/').get('csrftoken')
        return filter_dict({
            'X-IG-App-ID': self._WEB_APP_ID,
            'X-ASBD-ID': '359341',
            'X-IG-WWW-Claim': '0',
            'X-Requested-With': 'XMLHttpRequest',
            'X-CSRFToken': csrf.value if csrf else None,
            'Origin': 'https://www.instagram.com',
            'Referer': 'https://www.instagram.com/',
            'Accept': '*/*',
        })

    def _api(self, path, video_id, note, query=None):
        """The same call on the website's API, then on the app's; the answers and why they failed."""
        # the website's API first (its video streams are H.264 / VP9); with app_id=ios (how YTindir
        # gets the app's higher quality xHE-AAC audio) the app's API first
        for app in ((False, True) if self._is_web_app else (True, False)):
            base = self._APP_API if app else self._WEB_API
            try:
                data = self._download_json(
                    f'{base}/{path}', video_id, f'{note} ({"app" if app else "web"})',
                    query=query, headers=self._headers(app))
                if isinstance(data, dict) and data.get('status') != 'fail':
                    return data
                self._failures.append(f'{"app" if app else "web"} {path}: {traverse_obj(data, "message") or "no data"}')
            except ExtractorError as e:
                reason = f'HTTP {e.cause.status}' if isinstance(e.cause, HTTPError) else type(e.cause or e).__name__
                self._failures.append(f'{"app" if app else "web"} {path}: {reason}')
        return None

    def _entry(self, item, user_info=None):
        if user_info:
            item.setdefault('user', {}).update(user_info)
        data = self._extract_product(item, get_comments=False)
        if not data.get('formats'):
            return None
        user = traverse_obj(item, ('user', {dict})) or {}
        return {
            'uploader': user.get('full_name'),
            'uploader_id': str_or_none(user.get('pk') or user.get('id')),
            **filter_dict(data),
        }

    def _user_id(self, username):
        info = self._api('users/web_profile_info/', username, 'Downloading user info', {'username': username})
        user_id = traverse_obj(info, ('data', 'user', 'id', {str_or_none}))
        if user_id:
            return user_id
        info = self._api(f'users/{username}/usernameinfo/', username, 'Downloading user info')
        return traverse_obj(info, ('user', 'pk', {str_or_none}), ('user', 'pk_id', {str_or_none}))

    def _ytindir_extract(self, url):
        username, story_id = self._match_valid_url(url).group('user', 'id')
        if not self._is_logged_in:
            # Instagram shows stories only to logged-in accounts
            raise ExtractorError(
                'Instagram stories can only be downloaded with an Instagram account: '
                'log in to Instagram in Cookies and turn Cookies on', expected=True)
        self._share_cookies()
        display_id = story_id or username

        # a story link opens that one story (as in the app): its own media info
        if username != 'highlights' and story_id:
            info = self._api(f'media/{story_id}/info/', story_id, 'Downloading story info')
            item = traverse_obj(info, ('items', 0, {dict}))
            if item:
                entry = self._entry(item)
                if entry:
                    return entry
                raise ExtractorError('This story is a photo; only video stories can be downloaded', expected=True)

        if username == 'highlights':
            if not story_id:
                raise ExtractorError('Input URL is missing a highlight ID', expected=True)
            reel_id = f'highlight:{story_id}'
        else:
            reel_id = self._user_id(username)
            if not reel_id:
                raise ExtractorError('Unable to extract user id')

        reels = traverse_obj(self._api('feed/reels_media/', display_id, 'Downloading stories', {'reel_ids': reel_id}), 'reels')
        reel = traverse_obj(reels, (reel_id, {dict}))
        if not reel:
            raise ExtractorError('no stories in the answer')

        user_info = traverse_obj(reel, ('user', {dict})) or {}
        entries = [e for e in (self._entry(item, user_info) for item in traverse_obj(reel, ('items', ..., {dict}))) if e]
        if not entries:
            raise ExtractorError('These stories have no videos', expected=True)

        if username != 'highlights' and story_id:
            single = traverse_obj(entries, (lambda _, v: v['id'] == _pk_to_id(story_id), any))
            if single:
                return single
            raise ExtractorError('This story is no longer available or is a photo', expected=True)

        title = reel.get('title') or f'Story by {username}'
        return self.playlist_result(entries, playlist_id=story_id or reel_id, playlist_title=title)

    def _real_extract(self, url):
        self._failures = []
        try:
            return self._ytindir_extract(url)
        except ExtractorError as e:
            if e.expected:
                raise
            reason = '; '.join(self._failures) or e.msg
            self.report_warning(f'YTindir story API failed ({reason}), trying yt-dlp\'s own way')
            return super()._real_extract(url)
