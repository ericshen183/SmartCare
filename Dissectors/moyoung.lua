-- Copyright (C) 2024 Arjan Schrijver
--
-- This file is part of Gadgetbridge-tools.
--
-- Gadgetbridge is free software: you can redistribute it and/or modify
-- it under the terms of the GNU Affero General Public License as published
-- by the Free Software Foundation, either version 3 of the License, or
-- (at your option) any later version.
--
-- Gadgetbridge is distributed in the hope that it will be useful,
-- but WITHOUT ANY WARRANTY; without even the implied warranty of
-- MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
-- GNU Affero General Public License for more details.
--
-- You should have received a copy of the GNU Affero General Public License
-- along with this program.  If not, see <https://www.gnu.org/licenses/>.

print("Dissector for the MOYOUNG BLE protocol")

-- all commands that are not set/get pairs with 0x10 offsets
local moyoung_cmd = {
    [0x12] = "Set user info",
    [0x29] = "Get watchface",
    [0x31] = "Set time",
    [0x32] = "Sync sleep data",
    [0x33] = "Sync past sleep and step data",
    [0x34] = "Query last dynamic rate => Workout FINISHED",
    [0x35] = "Get historical heart rate values",
    [0x36] = "Get historical heart rate values v2",
    [0x37] = "Get movement HR",
    [0x38] = "Set watchface layout",
    [0x39] = "Get watchface layout",
    [0x41] = "Send notification",
    [0x42] = "Set weather forecast",
    [0x43] = "Set weather today",
    [0x44] = "Set music info",
    [0x45] = "Set weather location",
    [0x59] = "Sync historical step values",
    [0x66] = "Camera action",
    [0x67] = "Notify phone operation",
    [0x68] = "Start/stop dynamic HR measurement",
    [0x6d] = "Manual HR measurement",
    [0x7b] = "Set music state",
    [0x82] = "Get quick view time",
    [0x84] = "Get supported watchfaces",
    [0xb2] = "Workout",
    [0xb5] = "Set weather sunrise/sunset",
    [0xb9] = "Advanced",
    [0xbb] = "World clock command",
    [0xf2] = "Set favorite contact",
}

-- commands that have a Set and a Get at +0x10
local moyoung_set_get = {
    [0x11] = "alarms",
    [0x15] = "display device function",
    [0x17] = "time system",
    [0x18] = "quick view",
    [0x19] = "watchface from app",
    [0x1a] = "measurement system",
    [0x1c] = "other message",
    [0x1d] = "movement reminder",
    [0x1f] = "HR measurement interval",
    [0x71] = "DND time period",
    [0x73] = "movement reminder period",
    [0x94] = "energy saving mode",
}

for id, name in pairs(moyoung_set_get) do
    moyoung_cmd[id] = "Set " .. name;
    moyoung_cmd[id+0x10] = "Get " .. name;
end

-- TODO: sub-commands for 0x44, 0x5a?, 0xb2, 0xb7?, 0xbb
-- sub-commands for 0xb2
moyoung_workout_cmd = {
    [0x00] = "list request",
    [0x01] = "list response",
    [0x02] = "detail request",
    [0x03] = "detail response",
    [0x04] = "HR request",
    [0x05] = "HR response",
}
-- sub-commands for 0xb9
moyoung_advanced_cmd = {
    [0x01] = "single stress measurement",
    [0x03] = "workout detail",
    [0x05] = "set alarm",
    [0x08] = "calendar sync",
    [0x0e] = "stocks sync",
    [0x11] = "stress measurement",
    [0x15] = "get alarms",
}

moyoung = Proto("MOYOUNG",  "MOYOUNG BLE Protocol")
moyoung.fields.uuid = ProtoField.uint16("moyoung.uuid", "DaFit UUID", base.HEX)
moyoung.fields.size = ProtoField.uint16("moyoung.size", "Size", base.DEC, nil, 0x1fff)
moyoung.fields.cmd = ProtoField.uint8("moyoung.cmd", "Command", base.HEX, moyoung_cmd)
moyoung.fields.advanced_cmd = ProtoField.uint8("moyoung.advanced_cmd", "Advanced command", base.HEX, moyoung_advanced_cmd)
moyoung.fields.workout_cmd = ProtoField.uint8("moyoung.workout_cmd", "Workout command", base.HEX, moyoung_workout_cmd)
moyoung.fields.size_bytes = ProtoField.uint8("moyoung.size_bytes", "Size in bytes", base.DEC)
moyoung.fields.packet_nr = ProtoField.uint8("moyoung.packet_nr", "Packet number", base.DEC)
moyoung.fields.unhandled_bytes = ProtoField.bytes("moyoung.unhandled_bytes", "Unhandled bytes", base.SPACE)
moyoung.fields.unhandled_int = ProtoField.uint8("moyoung.unhandled_int", "Unhandled integer", base.DEC)
moyoung.fields.user_height = ProtoField.uint8("moyoung.user_height", "Height", base.DEC)
moyoung.fields.user_weight = ProtoField.uint8("moyoung.user_weight", "Weight", base.DEC)
moyoung.fields.user_age = ProtoField.uint8("moyoung.user_age", "Age", base.DEC)
moyoung.fields.user_gender = ProtoField.uint8("moyoung.user_gender", "Gender", base.DEC)
moyoung.fields.watchface_nr = ProtoField.uint8("moyoung.watchface_nr", "Watchface number", base.DEC)
moyoung.fields.time = ProtoField.string("moyoung.time", "Time", base.STRING)
moyoung.fields.hr_auto_interval = ProtoField.uint8("moyoung.hr_auto_interval", "Heart rate measurement interval", base.DEC)
moyoung.fields.energy_saving = ProtoField.bool("moyoung.energy_saving", "Energy Saving", base.BOOL)
moyoung.fields.movement_reminder = ProtoField.bool("moyoung.movement_reminder", "Movement Reminder", base.BOOL)
moyoung.fields.minutes = ProtoField.uint16("moyoung.minutes", "Minutes since midnight", base.DEC)
moyoung.fields.text = ProtoField.string("moyoung.text", "Text", base.STRING)
moyoung.fields.stock_nr = ProtoField.uint8("moyoung.stock_nr", "Stock number", base.DEC)
moyoung.fields.offset_secs = ProtoField.int16("moyoung.offset_secs", "Offset in seconds", base.DEC)
moyoung.fields.avg_hr = ProtoField.uint8("moyoung.avg_hr", "Average HR", base.DEC)
moyoung.fields.distance = ProtoField.uint8("moyoung.distance", "Distance in m", base.DEC)
moyoung.fields.duration = ProtoField.uint8("moyoung.duration", "Duration in s", base.DEC)
moyoung.fields.kcal = ProtoField.uint8("moyoung.kcal", "Kcal burned", base.DEC)
moyoung.fields.steps = ProtoField.uint8("moyoung.steps", "Steps", base.DEC)
moyoung.fields.stress = ProtoField.uint8("moyoung.stress", "Stress", base.DEC)
moyoung.fields.weather_condition = ProtoField.uint8("moyoung.weather_condition", "Condition ID", base.DEC)
moyoung.fields.weather_cur_temp = ProtoField.uint8("moyoung.weather_cur_temp", "Current temperature", base.DEC)
moyoung.fields.weather_min_temp = ProtoField.uint8("moyoung.weather_min_temp", "Minimum temperature", base.DEC)
moyoung.fields.weather_max_temp = ProtoField.uint8("moyoung.weather_max_temp", "Maximum temperature", base.DEC)

local btatt_handle = Field.new("btatt.handle")
local handle_write_command_1 = 0x0045
local handle_write_command_2 = 0x004e  -- send file
local handle_read_request = 0x0042
local handle_value_notify_1 = 0x0028
local handle_value_notify_2 = 0x0047
local handle_value_notify_3 = 0x0022
local handle_value_notify_4 = 0x0064

local btatt_opcode_f = Field.new("btatt.opcode")

function minutes_since_midnight(mins)
    h = math.floor(mins/60)
    m = mins % 60
    return string.format("%02d:%02d", h, m)
end

function moyoung.dissector(buffer, pinfo, tree)
    if buffer:len() > 4 and buffer(0, 2):uint() == 0xfeea then
	-- get parent message type
	local info_prefix = ""
	local foo = { btatt_opcode_f() }
	if #foo > 0 then
	    if foo[1].value == 0x52 then
		info_prefix = "WRITE "
	    elseif foo[1].value == 0x1b then
		info_prefix = "NOTIFY "
	    end
	end
	pinfo.cols.protocol = moyoung.name
	local subtree = tree:add(moyoung, buffer(), "MOYOUNG protocol message")
        subtree:add(moyoung.fields.uuid, buffer(0, 2))
        subtree:add(moyoung.fields.size, buffer(2, 2))
	-- parse command
	cmd = buffer(4, 1):uint()
        subtree:add(moyoung.fields.cmd, buffer(4, 1))
	info = moyoung_cmd[cmd] --or "Unknown cmd 0x" .. tostring(buffer(4, 1))
	if cmd == 0xb2 then
	    -- parse workout command
	    workout_cmd = buffer(5, 1):uint()
	    subtree:add(moyoung.fields.workout_cmd, buffer(5, 1))
	    workout_info = ": " .. (moyoung_workout_cmd[workout_cmd] or "unknown 0x" .. tostring(buffer(5, 1)))
	    info = info .. workout_info
	    has_data = buffer:len() > 6
        elseif cmd == 0xb9 then
	    -- parse advanced command
	    advanced_cmd = buffer(5, 1):uint()
	    subtree:add(moyoung.fields.advanced_cmd, buffer(5, 1))
	    advanced_info = ": " .. (moyoung_advanced_cmd[advanced_cmd] or "0x" .. tostring(buffer(5, 1)))
	    info = info .. advanced_info
	    has_data = buffer:len() > 6
	else
	    has_data = buffer:len() > 5
	end
	if info then
            if has_data then
                pinfo.cols.info = info_prefix .. info .. " (with data)"
            else
                pinfo.cols.info = info_prefix .. info
            end
        end
        if cmd == 0x12 then
	    -- 0x12 "Set user info"
            if buffer:len() >= 6 then
                subtree:add(moyoung.fields.user_height, buffer(5, 1))
                subtree:add(moyoung.fields.user_weight, buffer(6, 1))
                subtree:add(moyoung.fields.user_age, buffer(7, 1))
                subtree:add(moyoung.fields.user_gender, buffer(8, 1))
            end
        elseif cmd == 0x19 or cmd == 0x29 then
	    -- 0x19 "Get watchface from app"
	    -- 0x29 "Set watchface from app"
            if buffer:len() >= 6 then
                subtree:add(moyoung.fields.watchface_nr, buffer(5, 1))
		pinfo.cols.info:append(": " .. buffer(5, 1):uint())
            end
        elseif cmd == 0x31 then
            -- "Set time"
            local time = buffer(5, 4):uint()
            local offset = buffer(9, 1):int()
            local time_str = os.date("%Y/%m/%d %H:%M:%S", time) .. " (offset " .. offset .. " hours)"
            pinfo.cols.info:append(": " .. time_str)
            subtree:add(moyoung.fields.time, time_str)
        elseif cmd == 0x1f or cmd == 0x2f then
            -- 0x1f "Set HR measurement interval"
            -- 0x2f "Query HR measurement interval"
            if buffer:len() >= 6 then
                rate = (buffer(5, 1):uint()*5) .. "min"
                subtree:add(moyoung.fields.hr_auto_interval, buffer(5, 1)):append_text(" = " .. rate)
		pinfo.cols.info:append(": " .. rate)
            end
        elseif cmd == 0x68 then
            -- "Start/stop dynamic HR measurement"
        elseif cmd == 0xa4 or cmd == 0x94 then
            -- 0x94/0xa4 "Set/query energy saving mode"
	    if buffer:len() > 5 then
		subtree:add(moyoung.fields.energy_saving, buffer(6, 1))
		pinfo.cols.info:append(": " .. buffer(6, 1):int())
	    end
        elseif cmd == 0x2c or cmd == 0x1c then
            -- 0x1c/0x2c "Set/query other message"
	    if buffer:len() > 5 then
		subtree:add(moyoung.fields.unhandled_int, buffer(5, buffer:len() - 5)):append_text(" = other message state")
		pinfo.cols.info:append(": " .. buffer(5, 1):int())
	    end
        elseif cmd == 0x2a or cmd == 0x1a then
            -- 0x1a/0x2a "Set/query measurement system"
	    if buffer:len() > 5 then
		subtree:add(moyoung.fields.unhandled_bytes, buffer(5, buffer:len() - 5))
	    end
        elseif cmd == 0x27 or cmd == 0x17 then
            -- 0x17/0x27 "Set/query time system"
	    if buffer:len() > 5 then
		subtree:add(moyoung.fields.unhandled_bytes, buffer(5, buffer:len() - 5))
	    end
        elseif cmd == 0x84 then
            -- "Query supported watchfaces"
            subtree:add(moyoung.fields.unhandled_bytes, buffer(5, buffer:len() - 5))
        elseif cmd == 0x28 or cmd == 0x18 then
            -- 0x18/0x28 "Set/query quick view"
            subtree:add(moyoung.fields.unhandled_bytes, buffer(5, buffer:len() - 5))
        elseif cmd == 0x1d or cmd == 0x2d then
            -- 0x1d/0x2d "Set/query movement reminder"
	    if buffer:len() > 5 then
                subtree:add(moyoung.fields.movement_reminder, buffer(5, 1))
		pinfo.cols.info:append(": " .. buffer(5, 1):int())
            end
        elseif cmd == 0x73 then
            -- 0x73/0x83 "Set/query movement reminder period"
            subtree:add(moyoung.fields.unhandled_bytes, buffer(5, buffer:len() - 5))
        elseif cmd == 0x81 or cmd == 0x71 or cmd == 0x82 then
            -- 0x82 "Query quick view time"
            -- 0x81/0x71 "Set/query DND time period"
	    if buffer:len() > 5 then
		t1 = minutes_since_midnight(buffer(5, 2):le_uint())
		t2 = minutes_since_midnight(buffer(7, 2):le_uint())
		subtree:add(moyoung.fields.minutes, buffer(5, 2)):append_text(" (" .. t1 .. ")")
		subtree:add(moyoung.fields.minutes, buffer(7, 2)):append_text(" (" .. t2 .. ")")
		pinfo.cols.info:append(": " .. t1 .. "-" .. t2)
	    end
        elseif cmd == 0x25 then
            -- "Query display device function"
        elseif cmd == 0x21 then
            -- "Query alarms"
        elseif cmd == 0x67 then
            -- "Notify phone operation"
            subtree:add(moyoung.fields.unhandled_bytes, buffer(5, 1)):append_text(" = operation")
            subtree:add(moyoung.fields.unhandled_bytes, buffer(6, buffer:len() - 6))
        elseif cmd == 0x15 then
            -- "Set display device function"
        elseif cmd == 0x39 or cmd == 0x38 then
	    -- 0x38 = "Set watch face layout"
	    -- 0x39 = "Get watch face layout"
            if buffer:len() > 5 then
                subtree:add(moyoung.fields.unhandled_bytes, buffer(5, 1)):append_text(" = time_position")
                subtree:add(moyoung.fields.unhandled_bytes, buffer(6, 1)):append_text(" = time_top_content")
                subtree:add(moyoung.fields.unhandled_bytes, buffer(7, 1)):append_text(" = time_bottom_content")
                subtree:add(moyoung.fields.unhandled_bytes, buffer(8, 2)):append_text(" = text_color")
                subtree:add(moyoung.fields.unhandled_bytes, buffer(10, 32)):append_text(" = background_picture_md5")
                subtree:add(moyoung.fields.unhandled_bytes, buffer(42, buffer:len() - 42))
            end
        elseif cmd == 0x41 then
            -- "Send notification"
            subtree:add(moyoung.fields.unhandled_bytes, buffer(5, 1))
            subtree:add(moyoung.fields.text, buffer(6, buffer:len() - 6))
        elseif cmd == 0x35 then
            -- "Historical heart rate values"
            subtree:add(moyoung.fields.packet_nr, buffer(5, 1)):append_text(" (quarters of days ago)")
            subtree:add(moyoung.fields.unhandled_bytes, buffer(6, buffer:len() - 6))
        elseif cmd == 0x36 then
            -- "Historical heart rate values v2"
        elseif cmd == 0x37 then
            -- "Query movement HR"
        elseif cmd == 0xf2 then
            -- "Set favorite contact"
        elseif cmd == 0x7b then
            -- "Send music state"
            subtree:add(moyoung.fields.unhandled_bytes, buffer(5, 1)):append_text(" (00 = stopped, 01 = playing)")
        elseif cmd == 0x42 then
            -- "Set weather forecast"
            subtree:add(moyoung.fields.weather_condition, buffer(5, 1)):append_text(" = today")
            subtree:add(moyoung.fields.weather_max_temp, buffer(6, 1)):append_text(" = today")
            subtree:add(moyoung.fields.weather_min_temp, buffer(7, 1)):append_text(" = today")
            subtree:add(moyoung.fields.weather_condition, buffer(8, 1)):append_text(" = 1 day ahead")
            subtree:add(moyoung.fields.weather_max_temp, buffer(9, 1)):append_text(" = 1 day ahead")
            subtree:add(moyoung.fields.weather_min_temp, buffer(10, 1)):append_text(" = 1 day ahead")
            subtree:add(moyoung.fields.weather_condition, buffer(11, 1)):append_text(" = 2 days ahead")
            subtree:add(moyoung.fields.weather_max_temp, buffer(12, 1)):append_text(" = 2 days ahead")
            subtree:add(moyoung.fields.weather_min_temp, buffer(13, 1)):append_text(" = 2 days ahead")
            subtree:add(moyoung.fields.weather_condition, buffer(14, 1)):append_text(" = 3 days ahead")
            subtree:add(moyoung.fields.weather_max_temp, buffer(15, 1)):append_text(" = 3 days ahead")
            subtree:add(moyoung.fields.weather_min_temp, buffer(16, 1)):append_text(" = 3 days ahead")
            subtree:add(moyoung.fields.weather_condition, buffer(17, 1)):append_text(" = 4 days ahead")
            subtree:add(moyoung.fields.weather_max_temp, buffer(18, 1)):append_text(" = 4 days ahead")
            subtree:add(moyoung.fields.weather_min_temp, buffer(19, 1)):append_text(" = 4 days ahead")
            subtree:add(moyoung.fields.weather_condition, buffer(20, 1)):append_text(" = 5 days ahead")
            subtree:add(moyoung.fields.weather_max_temp, buffer(21, 1)):append_text(" = 5 days ahead")
            subtree:add(moyoung.fields.weather_min_temp, buffer(22, 1)):append_text(" = 5 days ahead")
            subtree:add(moyoung.fields.weather_condition, buffer(23, 1)):append_text(" = 6 days ahead")
            subtree:add(moyoung.fields.weather_max_temp, buffer(24, 1)):append_text(" = 6 days ahead")
            subtree:add(moyoung.fields.weather_min_temp, buffer(25, 1)):append_text(" = 6 days ahead")
        elseif cmd == 0x43 then
            -- "Send weather info today"
            subtree:add(moyoung.fields.unhandled_bytes, buffer(5, 1)):append_text(" = pm25 available?")
            subtree:add(moyoung.fields.weather_condition, buffer(6, 1))
            subtree:add(moyoung.fields.weather_cur_temp, buffer(7, 1))
            subtree:add(moyoung.fields.unhandled_bytes, buffer(8, buffer:len() - 8))
        elseif cmd == 0xb5 then
            -- "Send weather info sunrise/sunset"
            subtree:add(moyoung.fields.unhandled_bytes, buffer(5, 1))
            subtree:add(moyoung.fields.weather_condition, buffer(6, 1))
            subtree:add(moyoung.fields.weather_cur_temp, buffer(7, 1))
            subtree:add(moyoung.fields.unhandled_bytes, buffer(8, 2))
            subtree:add(moyoung.fields.text, buffer(10, 1):uint() .. ":" .. buffer(11, 1):uint())
            subtree:add(moyoung.fields.text, buffer(12, 1):uint() .. ":" .. buffer(13, 1):uint())
            subtree:add(moyoung.fields.text, buffer(14, buffer:len() - 14))
        elseif cmd == 0x45 then
            -- "Send weather location"
            subtree:add(moyoung.fields.text, buffer(5, buffer:len() - 5))
        elseif cmd == 0x66 then
            -- "Camera action"
        elseif cmd == 0x32 then
            -- "Sync sleep data"
        elseif cmd == 0x33 then
            -- "Sync past sleep and step data"
        elseif cmd == 0x59 then
            -- "Historical steps values"
            subtree:add(moyoung.fields.packet_nr, buffer(5, 1)):append_text(" (days ago)")
            subtree:add(moyoung.fields.unhandled_bytes, buffer(6, buffer:len() - 6))
        elseif cmd == 0x6d then
            -- "Manual HR measurement"
            subtree:add(moyoung.fields.unhandled_bytes, buffer(5, buffer:len() - 5))
        elseif buffer:len() >= 6 and buffer(4, 2):uint() == 0x4400 then
            pinfo.cols.info = info_prefix .. "Send music track information"
            subtree:add(moyoung.fields.text, buffer(6, buffer:len() - 6))
        elseif buffer:len() >= 6 and buffer(4, 2):uint() == 0x4401 then
            pinfo.cols.info = info_prefix .. "Send music artist information"
            subtree:add(moyoung.fields.text, buffer(6, buffer:len() - 6))
        elseif buffer:len() >= 7 and buffer(4, 3):uint() == 0xbb0002 then
            pinfo.cols.info = info_prefix .. "Set world clock"
            subtree:add(moyoung.fields.unhandled_bytes, buffer(7, 1)):append_text(" = world clock nr")
	    subtree:add(moyoung.fields.offset_secs, buffer(8, 4):le_int()):append_text(" = vs UTC")
            subtree:add(moyoung.fields.unhandled_bytes, buffer(12, 8))
	    subtree:add(moyoung.fields.offset_secs, buffer(20, 4):le_int()):append_text(" = vs local")
            subtree:add(moyoung.fields.text, buffer(24, buffer:len() - 24))
        elseif buffer:len() >= 7 and buffer(4, 3):uint() == 0xbb0003 then
            pinfo.cols.info = info_prefix .. "Delete world clock"
            subtree:add(moyoung.fields.unhandled_bytes, buffer(7, 1)):append_text(" = world clock nr")
        elseif buffer:len() >= 6 and buffer(4, 2):uint() == 0xb203 then
            -- "Workout detail response"
            subtree:add(moyoung.fields.unhandled_bytes, buffer(6, 1)):append_text(" = workout nr")
            local time = buffer(7, 4):le_uint()
            subtree:add(moyoung.fields.time, os.date("%Y/%m/%d %H:%M:%S", time))
            local time = buffer(11, 4):le_uint()
            subtree:add(moyoung.fields.time, os.date("%Y/%m/%d %H:%M:%S", time))
            subtree:add_le(moyoung.fields.duration, buffer(15, 2))
            subtree:add(moyoung.fields.avg_hr, buffer(17, 1))
            subtree:add(moyoung.fields.unhandled_bytes, buffer(18, 1)):append_text(" = workout type (00=walking, 02=cycling)")
            subtree:add_le(moyoung.fields.steps, buffer(19, 4))
            subtree:add_le(moyoung.fields.distance, buffer(23, 4))
            subtree:add_le(moyoung.fields.kcal, buffer(27, 4))
        elseif buffer:len() >= 6 and buffer(4, 2):uint() == 0xb202 then
            -- "Workout detail request"
            subtree:add(moyoung.fields.unhandled_bytes, buffer(6, 1)):append_text(" = workout nr")
        elseif buffer:len() >= 6 and buffer(4, 2):uint() == 0xb201 then
            -- "Workouts list response"
            local time = buffer(6, 4):le_uint()
            subtree:add(moyoung.fields.time, os.date("%Y/%m/%d %H:%M:%S", time))
            subtree:add(moyoung.fields.unhandled_bytes, buffer(10, 1)):append_text(" = workout type (00=walking, 02=cycling)")
            local time = buffer(11, 4):le_uint()
            subtree:add(moyoung.fields.time, os.date("%Y/%m/%d %H:%M:%S", time))
            subtree:add(moyoung.fields.unhandled_bytes, buffer(15, 1)):append_text(" = workout type (00=walking, 02=cycling)")
            local time = buffer(16, 4):le_uint()
            subtree:add(moyoung.fields.time, os.date("%Y/%m/%d %H:%M:%S", time))
            subtree:add(moyoung.fields.unhandled_bytes, buffer(20, 1)):append_text(" = workout type (00=walking, 02=cycling)")
            local time = buffer(21, 4):le_uint()
            subtree:add(moyoung.fields.time, os.date("%Y/%m/%d %H:%M:%S", time))
            subtree:add(moyoung.fields.unhandled_bytes, buffer(25, 1)):append_text(" = workout type (00=walking, 02=cycling)")
            local time = buffer(26, 4):le_uint()
            subtree:add(moyoung.fields.time, os.date("%Y/%m/%d %H:%M:%S", time))
            subtree:add(moyoung.fields.unhandled_bytes, buffer(30, 1)):append_text(" = workout type (00=walking, 02=cycling)")
        elseif buffer:len() >= 6 and buffer(4, 2):uint() == 0xb200 then
            -- "Workouts list request"
        elseif buffer:len() >= 6 and buffer(4, 2):uint() == 0xb204 then
            -- "Workout HR request"
            subtree:add(moyoung.fields.unhandled_bytes, buffer(6, 1)):append_text(" = workout nr")
            subtree:add(moyoung.fields.unhandled_bytes, buffer(7, 2)):append_text(" = values index")
        elseif buffer:len() >= 6 and buffer(4, 2):uint() == 0xb205 then
            -- "Workout HR response"
            subtree:add(moyoung.fields.unhandled_bytes, buffer(6, 1)):append_text(" = workout nr")
            subtree:add(moyoung.fields.unhandled_bytes, buffer(7, 2)):append_text(" = values index after this packet")
            subtree:add(moyoung.fields.unhandled_bytes, buffer(9, buffer:len() - 9))
        elseif buffer:len() >= 5 and cmd == 0xb2 then
            -- "Workout unknown packet"
            subtree:add(moyoung.fields.unhandled_bytes, buffer(6, buffer:len() - 6))
        elseif buffer:len() >= 4 and cmd == 0x34 then
            pinfo.cols.info = info_prefix .. "Query last dynamic rate => Workout FINISHED"
            subtree:add(moyoung.fields.unhandled_bytes, buffer(5, 1)):append_text(" = packet subtype")
        elseif cmd == 0x11 then
            --pinfo.cols.info = "Set alarms"
            subtree:add(moyoung.fields.unhandled_bytes, buffer(5, 1)):append_text(" = alarm nr")
            subtree:add(moyoung.fields.unhandled_bytes, buffer(6, 1)):append_text(" = 1=enabled 0=disabled")
            subtree:add(moyoung.fields.unhandled_bytes, buffer(7, 1)):append_text(" = repeat enabled")
            subtree:add(moyoung.fields.unhandled_bytes, buffer(8, 1)):append_text(" = hour")
            subtree:add(moyoung.fields.unhandled_bytes, buffer(9, 1)):append_text(" = minute")
            subtree:add(moyoung.fields.unhandled_bytes, buffer(10, 1)):append_text(" = year+month")
            subtree:add(moyoung.fields.unhandled_bytes, buffer(11, 1)):append_text(" = day")
            subtree:add(moyoung.fields.unhandled_bytes, buffer(12, 1)):append_text(" = repetition days (byte mask)")
        elseif buffer:len() >= 6 and buffer(4, 2):uint() == 0xb901 then
            -- "Single stress measurement from app"
            subtree:add(moyoung.fields.unhandled_bytes, buffer(6, 2)):append_text(" = command")
        elseif buffer:len() >= 7 and buffer(4, 3):uint() == 0xb91100 then
            pinfo.cols.info = info_prefix .. "Single stress measurement result"
            subtree:add(moyoung.fields.unhandled_bytes, buffer(6, 1)):append_text(" = stress packet type")
            subtree:add(moyoung.fields.stress, buffer(7, 1))
        elseif buffer:len() == 8 and buffer(4, 2):uint() == 0xb911 then
            pinfo.cols.info = info_prefix .. "Stress measurements sync request"
            subtree:add(moyoung.fields.unhandled_bytes, buffer(6, 1)):append_text(" = stress packet type")
            subtree:add(moyoung.fields.unhandled_bytes, buffer(7, buffer:len() - 7)):append_text(" = days ago?")
        elseif buffer:len() > 8 and buffer(4, 2):uint() == 0xb911 then
            pinfo.cols.info = info_prefix .. "Stress measurements sync result"
            subtree:add(moyoung.fields.unhandled_bytes, buffer(6, 1)):append_text(" = stress packet type")
            subtree:add(moyoung.fields.unhandled_bytes, buffer(7, 1)):append_text(" = days ago?")
	    local index = 8
	    for i = 0, 23 do
		subtree:add(moyoung.fields.stress, buffer(index, 1)):append_text(" = " .. i .. ":00")
		index = index + 1
		subtree:add(moyoung.fields.stress, buffer(index, 1)):append_text(" = " .. i .. ":30")
		index = index + 1
	    end
        elseif buffer:len() >= 6 and buffer(4, 2):uint() == 0xb911 then
            -- "Stress measurement unknown"
            subtree:add(moyoung.fields.unhandled_bytes, buffer(6, 1)):append_text(" = stress packet type")
            subtree:add(moyoung.fields.unhandled_bytes, buffer(7, buffer:len() - 7))
        elseif buffer:len() >= 6 and buffer(4, 2):uint() == 0xb905 then
            -- "Set alarm from app"
            subtree:add(moyoung.fields.unhandled_bytes, buffer(6, 1))
            subtree:add(moyoung.fields.unhandled_bytes, buffer(7, 1)):append_text(" = alarm nr")
            subtree:add(moyoung.fields.unhandled_bytes, buffer(8, 1)):append_text(" = 1=enabled 0=disabled")
            subtree:add(moyoung.fields.unhandled_bytes, buffer(9, 1)):append_text(" = repeat enabled")
            subtree:add(moyoung.fields.unhandled_bytes, buffer(10, 1)):append_text(" = hour")
            subtree:add(moyoung.fields.unhandled_bytes, buffer(11, 1)):append_text(" = minute")
            subtree:add(moyoung.fields.unhandled_bytes, buffer(12, 1)):append_text(" = year+month")
            subtree:add(moyoung.fields.unhandled_bytes, buffer(13, 1)):append_text(" = day")
            subtree:add(moyoung.fields.unhandled_bytes, buffer(14, 1)):append_text(" = repetition days (byte mask)")
        elseif buffer:len() >= 6 and buffer(4, 2):uint() == 0xb915 then
            -- "Alarms set on watch"
            subtree:add(moyoung.fields.unhandled_bytes, buffer(6, 1))
            subtree:add(moyoung.fields.unhandled_bytes, buffer(7, 1)):append_text(" = amount of alarms in packet")
            subtree:add(moyoung.fields.unhandled_bytes, buffer(8, 1)):append_text(" = alarm nr")
            subtree:add(moyoung.fields.unhandled_bytes, buffer(9, 1)):append_text(" = 1=enabled 0=disabled")
            subtree:add(moyoung.fields.unhandled_bytes, buffer(10, 1)):append_text(" = repeat enabled")
            subtree:add(moyoung.fields.unhandled_bytes, buffer(11, 1)):append_text(" = hour")
            subtree:add(moyoung.fields.unhandled_bytes, buffer(12, 1)):append_text(" = minute")
            subtree:add(moyoung.fields.unhandled_bytes, buffer(13, 1)):append_text(" = year+month")
            subtree:add(moyoung.fields.unhandled_bytes, buffer(14, 1)):append_text(" = day")
            subtree:add(moyoung.fields.unhandled_bytes, buffer(15, 1)):append_text(" = repetition days (byte mask)")
        elseif buffer:len() >= 6 and buffer(4, 2):uint() == 0xb908 then
            -- "Calendar sync"
            subtree:add(moyoung.fields.unhandled_bytes, buffer(6, 1))
            subtree:add(moyoung.fields.packet_nr, buffer(7, 1))
            subtree:add(moyoung.fields.size_bytes, buffer(8, 1))
            local text_size = buffer(8, 1):uint()
            subtree:add(moyoung.fields.text, buffer(9, text_size))
            local from_hour = buffer(buffer:len() - 8, 1):uint()
            local from_min = buffer(buffer:len() - 7, 1):uint()
            local until_hour = buffer(buffer:len() - 6, 1):uint()
            local until_min = buffer(buffer:len() - 5, 1):uint()
            subtree:add(moyoung.fields.time, "From " .. from_hour .. ":" .. from_min .. " until " .. until_hour .. ":" .. until_min)
            local time = buffer(buffer:len() - 4, 4):le_uint()
            subtree:add(moyoung.fields.time, os.date("%Y/%m/%d %H:%M:%S", time))
        elseif buffer:len() >= 6 and buffer(4, 2):uint() == 0xb90e then
            -- "Stocks sync"
            subtree:add(moyoung.fields.packet_nr, buffer(6, 1))
            if buffer(6, 1):uint() == 2 then
                subtree:add(moyoung.fields.stock_nr, buffer(7, 1))
                subtree:add(moyoung.fields.text, buffer(8, 50))
                subtree:add(moyoung.fields.text, buffer(58, 30))
                subtree:add(moyoung.fields.text, buffer(88, 20))
                subtree:add(moyoung.fields.text, buffer(108, 10))
            elseif buffer(6, 1):uint() == 3 then
                subtree:add(moyoung.fields.stock_nr, buffer(7, 1))
                subtree:add(moyoung.fields.text, buffer(8, 8):le_int64() .. ""):append_text(" = price")
                subtree:add(moyoung.fields.text, buffer(16, 8):le_int64() .. ""):append_text(" = price change")
                subtree:add(moyoung.fields.text, buffer(24, 2):le_int()):append_text(" = price change %")
                subtree:add(moyoung.fields.text, buffer(26, 8):le_int64() .. ""):append_text(" = open")
                subtree:add(moyoung.fields.text, buffer(34, 8):le_int64() .. ""):append_text(" = high")
                subtree:add(moyoung.fields.text, buffer(42, 8):le_int64() .. ""):append_text(" = low")
                subtree:add(moyoung.fields.text, buffer(50, 8):le_int64() .. ""):append_text(" = 52W high")
                subtree:add(moyoung.fields.text, buffer(58, 8):le_int64() .. ""):append_text(" = 52W low")
                subtree:add(moyoung.fields.unhandled_bytes, buffer(64, 2))
                subtree:add(moyoung.fields.text, buffer(68, 8):le_int64() .. ""):append_text(" = market cap")
                subtree:add(moyoung.fields.text, buffer(76, 8):le_int64() .. ""):append_text(" = sales volume")
                subtree:add(moyoung.fields.text, buffer(84, 8):le_int64() .. ""):append_text(" = avg")
                subtree:add(moyoung.fields.unhandled_bytes, buffer(92, buffer:len() - 92)):append_text(" = market status (01 = closed)")
            end
        elseif cmd == 0xb9 then
            -- "Unknown advanced command"
            subtree:add(moyoung.fields.unhandled_bytes, buffer(6, buffer:len() - 6))
        else
	    -- unknown command
            subtree:add(moyoung.fields.unhandled_bytes, buffer(5, buffer:len() - 5))
        end
    end
end

btatt_handle_table = DissectorTable.get("btatt.handle")
btatt_handle_table:add(handle_write_command_1, moyoung)
btatt_handle_table:add(handle_write_command_2, moyoung)
btatt_handle_table:add(handle_read_request, moyoung)
btatt_handle_table:add(handle_value_notify_1, moyoung)
btatt_handle_table:add(handle_value_notify_2, moyoung)
btatt_handle_table:add(handle_value_notify_3, moyoung)
btatt_handle_table:add(handle_value_notify_4, moyoung)

