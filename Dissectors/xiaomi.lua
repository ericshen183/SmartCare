local utils = require("gb_utils")
local protobuf = Dissector.get("protobuf")
local gcrypt = require("luagcrypt")

xiaomi_proto = Proto("xiaomi", "Xiami Protocol")

xiaomi_proto.prefs.auth_key = Pref.string("Auth Key (hex)", "", "128-bit AES key, in hex")

GCRYPT_INITIALIZED = false

local CRYPTO_KEYS = {
    phoneNonce = '',
    watchNonce = '',
    decryptionKey = '',
    encryptionKey = '',
    decryptionNonce = '',
    encryptionNonce = '',
}

local function compute_auth_step3_hmac(secretKeyBytes, phoneNonceBytes, watchNonceBytes)
    local miwearAuthBytes = utils.str_to_bytes("miwear-auth")

    local mac1 = gcrypt.Hash(gcrypt.MD_SHA256, gcrypt.MD_FLAG_HMAC)
    mac1:setkey(utils.bytes_to_str(utils.bytes_concat(phoneNonceBytes, watchNonceBytes)))
    mac1:write(utils.bytes_to_str(secretKeyBytes))
    local hmacKeyBytes = mac1:read()

    local output = {}
    local tmp = ""
    local b = 1
    local i = 1

    while i <= 64 do
        local mac2 = gcrypt.Hash(gcrypt.MD_SHA256, gcrypt.MD_FLAG_HMAC)
        mac2:setkey(hmacKeyBytes)
        mac2:write(tmp .. utils.bytes_to_str(miwearAuthBytes) .. string.char(b))
        tmp = mac2:read()
        for j = 1, #tmp do
            if i > 64 then break end
            output[#output+1] = tmp:byte(j)
            i = i + 1
        end
        b = b + 1
    end

    return output
end

local function parse_command_plain(proto, payload, pinfo, subtree)
    -- Plain-text commands are always auth
    pinfo.private["pb_msg_type"] = "message,xiaomi.Command"
    pcall(Dissector.call, protobuf, payload(2, payload:len() - 2):tvb(), pinfo, subtree)

    -- HACK: Extract fields from protobuf manually. This is ugly.
    local subtype = payload(3, 1):uint()
    subtree:add(payload(3, 1), "subtype", payload(3, 1):uint())

    if subtype == 26 and utils.hci_h4_direction().value == 0x00 then
        CRYPTO_KEYS["phoneNonce"] = payload(11, 16):bytes():tohex()
        subtree:add(payload(11, 16), "phoneNonce", payload(11, 16):bytes():tohex())
    elseif subtype == 26 and utils.hci_h4_direction().value == 0x01 then
        CRYPTO_KEYS["watchNonce"] = payload(11, 16):bytes():tohex()
        subtree:add(payload(11, 16), "watchNonce", payload(11, 16):bytes():tohex())
    end

    if CRYPTO_KEYS["phoneNonce"] ~= nil and CRYPTO_KEYS["watchNonce"] ~= nil then
        subtree:add("Computing key...")
        subtree:add("  phoneNonce " .. CRYPTO_KEYS["phoneNonce"])
        subtree:add("  watchNonce " .. CRYPTO_KEYS["watchNonce"])
        subtree:add("  authKey " .. proto.prefs.auth_key)
        local hmac = compute_auth_step3_hmac(
            utils.str_to_bytes(utils.hex_to_bytes_str(proto.prefs.auth_key)),
            utils.str_to_bytes(utils.hex_to_bytes_str(CRYPTO_KEYS["phoneNonce"])),
            utils.str_to_bytes(utils.hex_to_bytes_str(CRYPTO_KEYS["watchNonce"]))
        )

        -- Lua is 1-based
        CRYPTO_KEYS["decryptionKey"]   = utils.bytes_str_to_hex(utils.bytes_to_str(utils.slice_bytes(hmac, 1, 16)))
        CRYPTO_KEYS["encryptionKey"]   = utils.bytes_str_to_hex(utils.bytes_to_str(utils.slice_bytes(hmac, 17, 32)))
        CRYPTO_KEYS["decryptionNonce"] = utils.bytes_str_to_hex(utils.bytes_to_str(utils.slice_bytes(hmac, 33, 36)))
        CRYPTO_KEYS["encryptionNonce"] = utils.bytes_str_to_hex(utils.bytes_to_str(utils.slice_bytes(hmac, 37, 40)))

        subtree:add("auth_step3_hmac " .. utils.bytes_str_to_hex(utils.bytes_to_str(hmac)))
        subtree:add("  decryptionKey " .. CRYPTO_KEYS["decryptionKey"])
        subtree:add("  encryptionKey " .. CRYPTO_KEYS["encryptionKey"])
        subtree:add("  decryptionNonce " .. CRYPTO_KEYS["decryptionNonce"])
        subtree:add("  encryptionNonce " .. CRYPTO_KEYS["encryptionNonce"])
    end
end

local function parse_ble_v2(proto, payload, pinfo, subtree)
    -- Preamble
    utils.assert_value(subtree, payload, 0, 0xa5)
    utils.assert_value(subtree, payload, 1, 0xa5)

    local packetType = utils.to_enum(
        subtree,
        {
            [1] = "ACK",
            [2] = "SESSION",
            [3] = "DATA",
        },
        payload(2, 1):le_uint()
    )
    pinfo.cols.info:append(" " .. packetType)

    subtree:add(payload(2, 1), "Packet Type:", packetType)
    subtree:add(payload(3, 1), "Sequence Number:", payload(3, 1):le_uint())
    subtree:add(payload(4, 2), "Payload Length:", payload(4, 2):le_uint())
    subtree:add(payload(6, 2), "Payload Checksum:", payload(6, 2):bytes():tohex())

    local dataPayload = payload(8, payload(4, 2):le_uint())
    subtree:add(dataPayload, "Payload:", dataPayload:bytes():tohex())
    utils.assert_length(subtree, payload, payload(4, 2):le_uint() + 8)

    if packetType == "DATA" then
        local CHANNELS = {
            [1] = "COMMAND",
            [2] = "DATA",
            [5] = "ACTIVITY",
        }
        local channel = utils.to_enum(subtree, CHANNELS, dataPayload(0, 1):le_uint())
        subtree:add(dataPayload(0, 1), "Channel:", channel)
        if channel == "COMMAND" then
            local commandPayload = dataPayload(2, dataPayload:len() - 2)
            if dataPayload(1, 1):uint() == 1 then
                -- plain-text
                subtree:add(commandPayload, "Plain-text Payload:", commandPayload:bytes():tohex())
                parse_command_plain(proto, dataPayload(2, dataPayload:len() - 2), pinfo, subtree)
            else
                -- encrypted
                subtree:add(commandPayload, "Encrypted Payload:", commandPayload:bytes():tohex())
                if gcrypt == nil then
                    utils.error(subtree, "lugcrypt not installed, unable to decrypt")
                    return
                end
                if not GCRYPT_INITIALIZED then
                    GCRYPT_INITIALIZED = true
                    gcrypt.init()
                end
                local cipher = gcrypt.Cipher(gcrypt.CIPHER_AES128, gcrypt.CIPHER_MODE_CTR)
                
                local decryptionKey = ""
                if utils.hci_h4_direction().value == 0x00 then
                    decryptionKey = CRYPTO_KEYS["encryptionKey"]
                elseif utils.hci_h4_direction().value == 0x01 then
                    decryptionKey = CRYPTO_KEYS["decryptionKey"]
                end
                --subtree:add(commandPayload, "decryptionKey:", decryptionKey)

                cipher:setkey(utils.hex_to_bytes_str(decryptionKey))
                cipher:setctr(utils.hex_to_bytes_str(decryptionKey))

                local decryptedByteString = cipher:decrypt(utils.hex_to_bytes_str(commandPayload:bytes():tohex()))
                local decryptedBytes = ByteArray.new(utils.bytes_str_to_hex(decryptedByteString):gsub(":", ""))
                local decryptedTvb = ByteArray.tvb(decryptedBytes(0, decryptedBytes:len()), "Decrypted Payload")
                subtree:add(decryptedTvb(0, decryptedTvb:len()), "Decrypted Payload:", decryptedTvb:bytes():tohex())

                pinfo.private["pb_msg_type"] = "message,xiaomi.Command"
                pcall(Dissector.call, protobuf, decryptedTvb, pinfo, subtree)
            end
        end
    end
end

utils.setup_base_dissector(xiaomi_proto, {
    -- Mi Band 9 Active
    [0x0032] = { "BLE V2 >", parse_ble_v2 },
    [0x002f] = { "BLE V2 <", parse_ble_v2 }
})
