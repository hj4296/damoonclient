-- 설정 탭 항목 (서버 저장과 클라이언트 적용이 같이 씀)
local Preferences = {}

Preferences.Items = {
	{ key = "camera", name = "시점", choices = { "first", "third" }, labels = { first = "1인칭", third = "3인칭" }, default = "first" },
	{ key = "textures", name = "블록 텍스처", choices = { true, false }, labels = { [true] = "켜기", [false] = "끄기" }, default = true },
	{
		key = "textureDistance",
		name = "텍스처 거리",
		choices = { 1, 2, 3 },
		labels = { [1] = "가까이", [2] = "보통", [3] = "멀리" },
		default = 2,
	},
	{ key = "sounds", name = "효과음", choices = { true, false }, labels = { [true] = "켜기", [false] = "끄기" }, default = true },
	{ key = "nametags", name = "다른 사람 이름표", choices = { true, false }, labels = { [true] = "보기", [false] = "숨기기" }, default = true },
	{ key = "chatFeed", name = "게임 알림 채팅", choices = { true, false }, labels = { [true] = "보기", [false] = "숨기기" }, default = true },
	{ key = "fly", name = "VIP 날기 (로비)", choices = { true, false }, labels = { [true] = "쓰기", [false] = "안 쓰기" }, default = true, vip = true },
}

Preferences.TextureRadius = { [1] = 90, [2] = 130, [3] = 190 }

function Preferences.defaults(): { [string]: any }
	local out = {}
	for _, item in Preferences.Items do
		out[item.key] = item.default
	end
	return out
end

-- 모르는 키나 잘못된 값은 기본값으로
function Preferences.sanitize(input: any): { [string]: any }
	local out = Preferences.defaults()
	if type(input) ~= "table" then
		return out
	end
	for _, item in Preferences.Items do
		local value = input[item.key]
		if table.find(item.choices, value) then
			out[item.key] = value
		end
	end
	return out
end

return Preferences
