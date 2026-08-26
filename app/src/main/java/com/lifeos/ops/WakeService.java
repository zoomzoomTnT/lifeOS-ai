package com.lifeos.ops;

import com.lifeos.domain.Memo;
import com.lifeos.domain.Receipt;
import com.lifeos.repo.MemoRepository;
import com.lifeos.repo.ReceiptRepository;
import com.lifeos.service.PersonService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class WakeService {

    private final MemoRepository memos;
    private final ReceiptRepository receipts;
    private final PersonService people;

    public Map<String, Object> shouldWake(String handle, int leadMinutes) {
        int minutes = Math.max(0, Math.min(leadMinutes, 72 * 60));
        long ownerId = people.resolveId(handle);
        boolean night = isTokyoNight();

        List<Memo> due = memos.dueForWake(ownerId, minutes, night);
        List<Receipt> staleReceipts = night ? List.of() : receipts.stalePending(ownerId, 24);

        List<String> reasons = new ArrayList<>();
        if (!due.isEmpty()) reasons.add("due_memos");
        if (!staleReceipts.isEmpty()) reasons.add("pending_receipts");
        boolean wake = !reasons.isEmpty();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("wake", wake);
        out.put("heartbeat_ok", !wake);
        out.put("night", night);
        out.put("timezone", "Asia/Tokyo");
        out.put("reasons", reasons);
        out.put("due_memos", due);
        out.put("pending_receipts", staleReceipts);
        out.put("instruction", wake
                ? "Speak at most 2 short WeChat messages, then POST /api/memos/{id}/fired. Do not use a vision model."
                : "Reply HEARTBEAT_OK. No other tools. No prose.");
        return out;
    }

    static boolean isTokyoNight() {
        int hour = ZonedDateTime.now(ZoneId.of("Asia/Tokyo")).getHour();
        return hour >= 22 || hour < 8;
    }
}
