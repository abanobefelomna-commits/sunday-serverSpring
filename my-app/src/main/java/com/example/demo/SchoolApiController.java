package com.example.demo;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
class SchoolApiController {
    private static final Set<String> GRADES =
            Set.of("first", "second", "third", "fourth", "fifth", "sixth", "general");
    private static final Set<String> ROLES =
            Set.of("خادم", "خادم رئيسي", "مشرف", "خادم فقرة عامة", "قائد فصل", "مسؤول", "أب كاهن");
    private static final Set<String> GENDERS = Set.of("male", "female");
    private static final Set<String> ATTENDANCE_STATUSES =
            Set.of("present", "absent", "late", "excused");
    private static final Set<String> VISITATION_TYPES =
            Set.of("home_visit", "phone_call", "church_meeting", "other");
    private static final Set<String> VISITATION_STATUSES =
            Set.of("planned", "completed", "cancelled");
    private static final RowMapper<Map<String, Object>> MAP_ROW =
            (rs, rowNum) -> {
                var result = new LinkedHashMap<String, Object>();
                var metadata = rs.getMetaData();
                for (int i = 1; i <= metadata.getColumnCount(); i++) {
                    Object value = rs.getObject(i);
                    if (value instanceof java.sql.Array array) {
                        value = List.of((Object[]) array.getArray());
                        array.free();
                    } else if (value != null
                            && value.getClass().getName().equals("org.postgresql.util.PGobject")) {
                        value = value.toString();
                    }
                    result.put(metadata.getColumnLabel(i), value);
                }
                return result;
            };

    private final JdbcTemplate jdbc;
    private final AuthService auth;
    private final Path uploadRoot;
    private final ObjectMapper objectMapper;
    private final boolean seedDefaultClasses;

    SchoolApiController(
            JdbcTemplate jdbc,
            AuthService auth,
            ObjectMapper objectMapper,
            @Value("${app.upload-dir:uploads}") String uploadDir,
            @Value("${app.seed-default-classes:true}") boolean seedDefaultClasses) {
        this.jdbc = jdbc;
        this.auth = auth;
        this.objectMapper = objectMapper;
        this.uploadRoot = Path.of(uploadDir).toAbsolutePath().normalize();
        this.seedDefaultClasses = seedDefaultClasses;
    }

    @EventListener(ApplicationReadyEvent.class)
    void seedDefaultClasses() {
        if (!seedDefaultClasses) return;
        List<List<String>> defaults = List.of(
                List.of("first", "فصل سنة أولى ابتدائي", "خدمة وتربية أطفال سنة أولى ابتدائي"),
                List.of("second", "فصل سنة ثانية ابتدائي", "خدمة وتربية أطفال سنة ثانية ابتدائي"),
                List.of("third", "فصل سنة ثالثة ابتدائي", "خدمة وتربية أطفال سنة ثالثة ابتدائي"),
                List.of("fourth", "فصل سنة رابعة ابتدائي", "خدمة وتربية أطفال سنة رابعة ابتدائي"),
                List.of("fifth", "فصل سنة خامسة ابتدائي", "خدمة وتربية أطفال سنة خامسة ابتدائي"),
                List.of("sixth", "فصل سنة سادسة ابتدائي", "خدمة وتربية أطفال سنة سادسة ابتدائي"),
                List.of("general", "فقرة عامة", "القسم العام لفقرة مدارس الأحد"));
        for (List<String> item : defaults) {
            Boolean exists = jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM classes WHERE grade = ?::classes_grade_enum)
                    """, Boolean.class, item.get(0));
            if (!Boolean.TRUE.equals(exists)) {
                jdbc.update("""
                        INSERT INTO classes (name, grade, description, "academicYear")
                        VALUES (?, ?::classes_grade_enum, ?, ?)
                        """, item.get(1), item.get(0), item.get(2), academicYear());
            }
        }
    }

    @GetMapping({"", "/"})
    String health() {
        return "Hello World!";
    }

    @PostMapping("/classes")
    Map<String, Object> createClass(
            @RequestBody Map<String, Object> body, HttpServletRequest request) {
        requireRole(request, "admin", "priest");
        String name = requiredText(body, "name");
        String grade = oneOf(body, "grade", GRADES, true);
        String id = jdbc.queryForObject("""
                INSERT INTO classes (name, grade, description, image, "academicYear")
                VALUES (?, ?::classes_grade_enum, ?, NULL, ?)
                RETURNING id
                """, String.class, name, grade, text(body, "description"), academicYear());
        return classById(id);
    }

    @GetMapping("/classes")
    Map<String, Object> classes(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String grade,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit,
            HttpServletRequest request) {
        requireUser(request);
        PageArguments paging = paging(page, limit, 100);
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        var args = new ArrayList<Object>();
        if (name != null && !name.isBlank()) {
            where.append(" AND c.name ILIKE ?");
            args.add("%" + name.trim() + "%");
        }
        if (grade != null && !grade.isBlank()) {
            where.append(" AND c.grade = ?::classes_grade_enum");
            args.add(oneOf(grade, GRADES, "grade"));
        }
        String sql = classSelect() + where + " ORDER BY c.\"createdAt\" DESC LIMIT ? OFFSET ?";
        args.add(paging.limit());
        args.add(paging.offset());
        return page(jdbc.query(sql, MAP_ROW, args.toArray()),
                count("classes c", where.toString(), args.subList(0, args.size() - 2)),
                paging);
    }

    @GetMapping("/classes/{id}")
    Map<String, Object> getClass(
            @PathVariable String id, HttpServletRequest request) {
        requireUser(request);
        return classById(uuid(id));
    }

    @PatchMapping("/classes/{id}")
    Map<String, Object> updateClass(
            @PathVariable String id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        requireRole(request, "admin", "priest");
        String classId = uuid(id);
        classById(classId);
        var updates = new ArrayList<String>();
        var args = new ArrayList<Object>();
        if (body.containsKey("name")) {
            updates.add("name = ?");
            args.add(requiredText(body, "name"));
        }
        if (body.containsKey("grade")) {
            updates.add("grade = ?::classes_grade_enum");
            args.add(oneOf(body, "grade", GRADES, true));
        }
        if (body.containsKey("description")) {
            updates.add("description = ?");
            args.add(text(body, "description"));
        }
        if (!updates.isEmpty()) {
            args.add(classId);
            jdbc.update("UPDATE classes SET " + String.join(", ", updates)
                    + ", \"updatedAt\" = now() WHERE id = ?::uuid", args.toArray());
        }
        return classById(classId);
    }

    @DeleteMapping("/classes/{id}")
    Map<String, String> deleteClass(
            @PathVariable String id, HttpServletRequest request) {
        requireRole(request, "admin", "priest");
        String classId = uuid(id);
        classById(classId);
        jdbc.update("DELETE FROM classes WHERE id = ?::uuid", classId);
        return Map.of("message", "تم حذف الفصل بنجاح");
    }

    @PatchMapping(value = "/classes/{id}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Map<String, Object> uploadClassImage(
            @PathVariable String id,
            @RequestParam("image") MultipartFile image,
            HttpServletRequest request) throws IOException {
        requireRole(request, "admin", "priest");
        String classId = uuid(id);
        Map<String, Object> cls = classById(classId);
        String url = saveImage(image, "classes");
        deleteImage((String) cls.get("image"));
        jdbc.update("UPDATE classes SET image = ?, \"updatedAt\" = now() WHERE id = ?::uuid",
                url, classId);
        return Map.of("message", "تم رفع الصورة بنجاح", "image", url);
    }

    @DeleteMapping("/classes/{id}/image")
    Map<String, Object> deleteClassImage(
            @PathVariable String id, HttpServletRequest request) {
        requireRole(request, "admin", "priest");
        String classId = uuid(id);
        Map<String, Object> cls = classById(classId);
        deleteImage((String) cls.get("image"));
        jdbc.update("UPDATE classes SET image = NULL, \"updatedAt\" = now() WHERE id = ?::uuid",
                classId);
        var result = new LinkedHashMap<String, Object>();
        result.put("message", "تم حذف الصورة بنجاح");
        result.put("image", null);
        return result;
    }

    @PostMapping("/children")
    Map<String, Object> createChild(
            @RequestBody Map<String, Object> body, HttpServletRequest request) {
        requireUser(request);
        String name = requiredText(body, "name");
        String birthDate = date(body, "birthDate", true);
        String gender = oneOf(body, "gender", GENDERS, true);
        String classId = optionalUuid(body, "classId");
        String id = jdbc.queryForObject("""
                INSERT INTO children
                    (name, "birthDate", gender, "parentPhone", "parentName", address, notes, "classId")
                VALUES (?, ?::date, ?::children_gender_enum, ?, ?, ?, ?, ?::uuid)
                RETURNING id
                """, String.class, name, birthDate, gender, text(body, "parentPhone"),
                text(body, "parentName"), text(body, "address"), text(body, "notes"), classId);
        return childById(id);
    }

    @GetMapping("/children")
    Map<String, Object> children(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String gender,
            @RequestParam(required = false) String classId,
            @RequestParam(required = false) Integer minAge,
            @RequestParam(required = false) Integer maxAge,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit,
            HttpServletRequest request) {
        requireUser(request);
        PageArguments paging = paging(page, limit, 100);
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        var args = new ArrayList<Object>();
        if (name != null && !name.isBlank()) {
            where.append(" AND ch.name ILIKE ?");
            args.add("%" + name.trim() + "%");
        }
        if (gender != null && !gender.isBlank()) {
            where.append(" AND ch.gender = ?::children_gender_enum");
            args.add(oneOf(gender, GENDERS, "gender"));
        }
        if (classId != null && !classId.isBlank()) {
            where.append(" AND ch.\"classId\" = ?::uuid");
            args.add(uuid(classId));
        }
        if (minAge != null) {
            where.append(" AND EXTRACT(YEAR FROM AGE(CURRENT_DATE, ch.\"birthDate\")) >= ?");
            args.add(nonNegative(minAge, "minAge"));
        }
        if (maxAge != null) {
            where.append(" AND EXTRACT(YEAR FROM AGE(CURRENT_DATE, ch.\"birthDate\")) <= ?");
            args.add(nonNegative(maxAge, "maxAge"));
        }
        String sql = childSelect() + where
                + " ORDER BY ch.\"createdAt\" DESC LIMIT ? OFFSET ?";
        args.add(paging.limit());
        args.add(paging.offset());
        var data = jdbc.query(sql, MAP_ROW, args.toArray());
        data.forEach(row -> row.put("class", classOrNull((String) row.get("classId"))));
        return page(data,
                count("children ch", where.toString(), args.subList(0, args.size() - 2)),
                paging);
    }

    @GetMapping("/children/{id}")
    Map<String, Object> getChild(
            @PathVariable String id, HttpServletRequest request) {
        requireUser(request);
        return childById(uuid(id));
    }

    @PatchMapping("/children/{id}")
    Map<String, Object> updateChild(
            @PathVariable String id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        requireUser(request);
        String childId = uuid(id);
        childById(childId);
        var updates = new ArrayList<String>();
        var args = new ArrayList<Object>();
        if (body.containsKey("name")) {
            updates.add("name = ?");
            args.add(requiredText(body, "name"));
        }
        if (body.containsKey("birthDate")) {
            updates.add("\"birthDate\" = ?::date");
            args.add(date(body, "birthDate", true));
        }
        if (body.containsKey("gender")) {
            updates.add("gender = ?::children_gender_enum");
            args.add(oneOf(body, "gender", GENDERS, true));
        }
        for (String field : List.of("parentPhone", "parentName", "address", "notes")) {
            if (body.containsKey(field)) {
                updates.add("\"" + field + "\" = ?");
                args.add(text(body, field));
            }
        }
        if (body.containsKey("classId")) {
            updates.add("\"classId\" = ?::uuid");
            args.add(optionalUuid(body, "classId"));
        }
        saveUpdates("children", childId, updates, args);
        return childById(childId);
    }

    @DeleteMapping("/children/{id}")
    Map<String, String> deleteChild(
            @PathVariable String id, HttpServletRequest request) {
        requireRole(request, "admin", "priest");
        String childId = uuid(id);
        childById(childId);
        jdbc.update("DELETE FROM children WHERE id = ?::uuid", childId);
        return Map.of("message", "تم حذف الطفل بنجاح");
    }

    @PostMapping("/servants")
    @Transactional
    Map<String, Object> createServant(
            @RequestBody Map<String, Object> body, HttpServletRequest request) {
        requireRole(request, "admin", "priest");
        String name = requiredText(body, "name");
        String phone = requiredText(body, "phone");
        String role = body.containsKey("role") ? oneOf(body, "role", ROLES, false) : "خادم";
        String id = jdbc.queryForObject("""
                INSERT INTO servants (name, phone, email, role, avatar)
                VALUES (?, ?, ?, ?::servants_role_enum, NULL)
                RETURNING id
                """, String.class, name, phone, text(body, "email"), role);
        if ("خادم فقرة عامة".equals(role) && !body.containsKey("classIds")) {
            jdbc.update("""
                    INSERT INTO servants_classes ("servantsId", "classesId")
                    SELECT ?::uuid, id FROM classes WHERE grade = 'general'::classes_grade_enum
                    ON CONFLICT DO NOTHING
                    """, id);
        } else {
            replaceServantClasses(id, body.get("classIds"));
        }
        return servantById(id);
    }

    @GetMapping("/servants")
    Map<String, Object> servants(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) String classId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit,
            HttpServletRequest request) {
        requireUser(request);
        PageArguments paging = paging(page, limit, 100);
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        var args = new ArrayList<Object>();
        if (name != null && !name.isBlank()) {
            where.append(" AND s.name ILIKE ?");
            args.add("%" + name.trim() + "%");
        }
        if (role != null && !role.isBlank()) {
            where.append(" AND s.role = ?::servants_role_enum");
            args.add(oneOf(role, ROLES, "role"));
        }
        if (phone != null && !phone.isBlank()) {
            where.append(" AND s.phone ILIKE ?");
            args.add("%" + phone.trim() + "%");
        }
        if (classId != null && !classId.isBlank()) {
            where.append("""
                     AND EXISTS (SELECT 1 FROM servants_classes sc
                       WHERE sc."servantsId" = s.id AND sc."classesId" = ?::uuid)
                    """);
            args.add(uuid(classId));
        }
        String sql = servantSelect() + where
                + " ORDER BY s.\"createdAt\" DESC LIMIT ? OFFSET ?";
        args.add(paging.limit());
        args.add(paging.offset());
        var data = jdbc.query(sql, MAP_ROW, args.toArray());
        data.forEach(row -> row.put("classes", classesForServant((String) row.get("id"))));
        return page(data,
                count("servants s", where.toString(), args.subList(0, args.size() - 2)),
                paging);
    }

    @GetMapping("/servants/{id}")
    Map<String, Object> getServant(
            @PathVariable String id, HttpServletRequest request) {
        requireUser(request);
        return servantById(uuid(id));
    }

    @PatchMapping("/servants/{id}")
    @Transactional
    Map<String, Object> updateServant(
            @PathVariable String id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        requireRole(request, "admin", "priest");
        String servantId = uuid(id);
        servantById(servantId);
        var updates = new ArrayList<String>();
        var args = new ArrayList<Object>();
        for (String field : List.of("name", "phone", "email")) {
            if (body.containsKey(field)) {
                updates.add("\"" + field + "\" = ?");
                args.add(field.equals("email") ? text(body, field) : requiredText(body, field));
            }
        }
        if (body.containsKey("role")) {
            updates.add("role = ?::servants_role_enum");
            args.add(oneOf(body, "role", ROLES, false));
        }
        saveUpdates("servants", servantId, updates, args);
        if (body.containsKey("classIds")) {
            replaceServantClasses(servantId, body.get("classIds"));
        }
        return servantById(servantId);
    }

    @DeleteMapping("/servants/{id}")
    Map<String, String> deleteServant(
            @PathVariable String id, HttpServletRequest request) {
        requireRole(request, "admin", "priest");
        String servantId = uuid(id);
        servantById(servantId);
        jdbc.update("DELETE FROM servants WHERE id = ?::uuid", servantId);
        return Map.of("message", "تم حذف الخادم بنجاح");
    }

    @PatchMapping(value = "/servants/{id}/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Map<String, Object> uploadServantAvatar(
            @PathVariable String id,
            @RequestParam("avatar") MultipartFile image,
            HttpServletRequest request) throws IOException {
        requireUser(request);
        String servantId = uuid(id);
        Map<String, Object> servant = servantById(servantId);
        String url = saveImage(image, "avatars");
        deleteImage((String) servant.get("avatar"));
        jdbc.update("UPDATE servants SET avatar = ?, \"updatedAt\" = now() WHERE id = ?::uuid",
                url, servantId);
        return Map.of("message", "تم رفع الصورة بنجاح", "avatar", url);
    }

    @DeleteMapping("/servants/{id}/avatar")
    Map<String, Object> deleteServantAvatar(
            @PathVariable String id, HttpServletRequest request) {
        requireUser(request);
        String servantId = uuid(id);
        Map<String, Object> servant = servantById(servantId);
        deleteImage((String) servant.get("avatar"));
        jdbc.update("UPDATE servants SET avatar = NULL, \"updatedAt\" = now() WHERE id = ?::uuid",
                servantId);
        var result = new LinkedHashMap<String, Object>();
        result.put("message", "تم حذف الصورة بنجاح");
        result.put("avatar", null);
        return result;
    }

    @PostMapping("/attendance/bulk")
    @Transactional
    Map<String, Object> recordAttendance(
            @RequestBody Map<String, Object> body, HttpServletRequest request) {
        Map<String, Object> user = requireUser(request);
        String classId = uuid(requiredText(body, "classId"));
        String date = date(body, "date", true);
        Object rawRecords = body.get("records");
        if (!(rawRecords instanceof List<?> records) || records.isEmpty()) {
            throw badRequest("لازم يكون فيه طفل واحد على الأقل");
        }
        String userId = (String) user.get("sub");
        for (Object value : records) {
            if (!(value instanceof Map<?, ?> record)) {
                throw badRequest("سجل الحضور غير صالح");
            }
            String childId = uuid(String.valueOf(record.get("childId")));
            String status = oneOf(record.get("status"), ATTENDANCE_STATUSES, "status");
            jdbc.update("""
                    INSERT INTO attendance ("childId", "classId", date, status, notes, "recordedBy")
                    VALUES (?::uuid, ?::uuid, ?::date, ?::attendance_status_enum, ?, ?::uuid)
                    ON CONFLICT ("childId", "classId", date)
                    DO UPDATE SET status = EXCLUDED.status, notes = EXCLUDED.notes,
                                  "recordedBy" = EXCLUDED."recordedBy"
                    """, childId, classId, date, status, nullableText(record.get("notes")), userId);
        }
        return Map.of("message", "تم تسجيل حضور " + records.size() + " طفل بنجاح",
                "date", date, "classId", classId);
    }

    @GetMapping("/attendance")
    Map<String, Object> attendance(
            @RequestParam(required = false) String classId,
            @RequestParam(required = false) String childId,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int limit,
            HttpServletRequest request) {
        requireUser(request);
        PageArguments paging = paging(page, limit, 100);
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        var args = new ArrayList<Object>();
        if (classId != null && !classId.isBlank()) {
            where.append(" AND a.\"classId\" = ?::uuid");
            args.add(uuid(classId));
        }
        if (childId != null && !childId.isBlank()) {
            where.append(" AND a.\"childId\" = ?::uuid");
            args.add(uuid(childId));
        }
        addDateFilter(where, args, "a.date", dateFrom, ">=");
        addDateFilter(where, args, "a.date", dateTo, "<=");
        if (status != null && !status.isBlank()) {
            where.append(" AND a.status = ?::attendance_status_enum");
            args.add(oneOf(status, ATTENDANCE_STATUSES, "status"));
        }
        String from = "attendance a LEFT JOIN children ch ON ch.id = a.\"childId\"";
        String sql = """
                SELECT a.*, to_jsonb(ch.*) AS child,
                       (SELECT to_jsonb(c.*) FROM classes c WHERE c.id = a."classId") AS class
                FROM
                """ + from + where + " ORDER BY a.date DESC, ch.name ASC LIMIT ? OFFSET ?";
        args.add(paging.limit());
        args.add(paging.offset());
        var data = jdbc.query(sql, MAP_ROW, args.toArray());
        data.forEach(row -> {
            row.put("child", readJson(row.get("child")));
            row.put("class", readJson(row.get("class")));
        });
        return page(data,
                count(from, where.toString(), args.subList(0, args.size() - 2)), paging);
    }

    @GetMapping("/attendance/summary")
    Map<String, Object> attendanceSummary(
            @RequestParam String classId,
            @RequestParam String date,
            HttpServletRequest request) {
        requireUser(request);
        String cls = uuid(classId);
        LocalDate day = parseDate(date);
        List<Map<String, Object>> records = jdbc.query("""
                SELECT a.*, to_jsonb(ch.*) AS child,
                       (SELECT to_jsonb(c.*) FROM classes c WHERE c.id = a."classId") AS class
                FROM attendance a JOIN children ch ON ch.id = a."childId"
                WHERE a."classId" = ?::uuid AND a.date = ?::date
                ORDER BY ch.name ASC
                """, MAP_ROW, cls, day);
        records.forEach(row -> {
            row.put("child", readJson(row.get("child")));
            row.put("class", readJson(row.get("class")));
        });
        var summary = new LinkedHashMap<String, Object>();
        summary.put("date", day);
        summary.put("classId", cls);
        summary.put("total", records.size());
        for (String state : ATTENDANCE_STATUSES) {
            summary.put(state, records.stream()
                    .filter(row -> state.equals(String.valueOf(row.get("status")))).count());
        }
        summary.put("records", records);
        return summary;
    }

    @GetMapping("/attendance/{id}")
    Map<String, Object> getAttendance(
            @PathVariable String id, HttpServletRequest request) {
        requireUser(request);
        return attendanceById(uuid(id));
    }

    @DeleteMapping("/attendance/{id}")
    Map<String, String> deleteAttendance(
            @PathVariable String id, HttpServletRequest request) {
        requireRole(request, "admin", "priest");
        String attendanceId = uuid(id);
        attendanceById(attendanceId);
        jdbc.update("DELETE FROM attendance WHERE id = ?::uuid", attendanceId);
        return Map.of("message", "تم حذف سجل الحضور");
    }

    @PostMapping("/visitations")
    Map<String, Object> createVisitation(
            @RequestBody Map<String, Object> body, HttpServletRequest request) {
        requireUser(request);
        String childId = uuid(requiredText(body, "childId"));
        String servantId = optionalUuid(body, "servantId");
        String date = date(body, "date", true);
        String type = body.containsKey("type")
                ? oneOf(body, "type", VISITATION_TYPES, false) : "home_visit";
        String status = body.containsKey("status")
                ? oneOf(body, "status", VISITATION_STATUSES, false) : "completed";
        String id = jdbc.queryForObject("""
                INSERT INTO visitations
                    ("childId", "servantId", date, type, status, notes, "followUpDate")
                VALUES (?::uuid, ?::uuid, ?::date, ?::visitations_type_enum,
                        ?::visitations_status_enum, ?, ?::date)
                RETURNING id
                """, String.class, childId, servantId, date, type, status,
                text(body, "notes"), optionalDate(body, "followUpDate"));
        return visitationById(id);
    }

    @GetMapping("/visitations")
    Map<String, Object> visitations(
            @RequestParam(required = false) String childId,
            @RequestParam(required = false) String servantId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int limit,
            HttpServletRequest request) {
        requireUser(request);
        PageArguments paging = paging(page, limit, 100);
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        var args = new ArrayList<Object>();
        if (childId != null && !childId.isBlank()) {
            where.append(" AND v.\"childId\" = ?::uuid");
            args.add(uuid(childId));
        }
        if (servantId != null && !servantId.isBlank()) {
            where.append(" AND v.\"servantId\" = ?::uuid");
            args.add(uuid(servantId));
        }
        if (type != null && !type.isBlank()) {
            where.append(" AND v.type = ?::visitations_type_enum");
            args.add(oneOf(type, VISITATION_TYPES, "type"));
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND v.status = ?::visitations_status_enum");
            args.add(oneOf(status, VISITATION_STATUSES, "status"));
        }
        addDateFilter(where, args, "v.date", dateFrom, ">=");
        addDateFilter(where, args, "v.date", dateTo, "<=");
        String from = "visitations v";
        String sql = """
                SELECT v.*,
                       (SELECT to_jsonb(ch.*) FROM children ch WHERE ch.id = v."childId") AS child,
                       (SELECT to_jsonb(s.*) FROM servants s WHERE s.id = v."servantId") AS servant
                FROM
                """ + from + where
                + " ORDER BY v.date DESC, v.\"createdAt\" DESC LIMIT ? OFFSET ?";
        args.add(paging.limit());
        args.add(paging.offset());
        var data = jdbc.query(sql, MAP_ROW, args.toArray());
        data.forEach(row -> {
            row.put("child", readJson(row.get("child")));
            row.put("servant", readJson(row.get("servant")));
        });
        return page(data,
                count(from, where.toString(), args.subList(0, args.size() - 2)), paging);
    }

    @GetMapping("/visitations/child/{childId}")
    List<Map<String, Object>> visitationsForChild(
            @PathVariable String childId, HttpServletRequest request) {
        requireUser(request);
        String child = uuid(childId);
        List<Map<String, Object>> records = jdbc.query("""
                SELECT v.*,
                       (SELECT to_jsonb(ch.*) FROM children ch WHERE ch.id = v."childId") AS child,
                       (SELECT to_jsonb(s.*) FROM servants s WHERE s.id = v."servantId") AS servant
                FROM visitations v WHERE v."childId" = ?::uuid
                ORDER BY v.date DESC
                """, MAP_ROW, child);
        records.forEach(row -> {
            row.put("child", readJson(row.get("child")));
            row.put("servant", readJson(row.get("servant")));
        });
        return records;
    }

    @GetMapping("/visitations/{id}")
    Map<String, Object> getVisitation(
            @PathVariable String id, HttpServletRequest request) {
        requireUser(request);
        return visitationById(uuid(id));
    }

    @PatchMapping("/visitations/{id}")
    Map<String, Object> updateVisitation(
            @PathVariable String id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        requireUser(request);
        String visitationId = uuid(id);
        visitationById(visitationId);
        var updates = new ArrayList<String>();
        var args = new ArrayList<Object>();
        if (body.containsKey("childId")) {
            updates.add("\"childId\" = ?::uuid");
            args.add(uuid(requiredText(body, "childId")));
        }
        if (body.containsKey("servantId")) {
            updates.add("\"servantId\" = ?::uuid");
            args.add(optionalUuid(body, "servantId"));
        }
        if (body.containsKey("date")) {
            updates.add("date = ?::date");
            args.add(date(body, "date", true));
        }
        if (body.containsKey("type")) {
            updates.add("type = ?::visitations_type_enum");
            args.add(oneOf(body, "type", VISITATION_TYPES, false));
        }
        if (body.containsKey("status")) {
            updates.add("status = ?::visitations_status_enum");
            args.add(oneOf(body, "status", VISITATION_STATUSES, false));
        }
        if (body.containsKey("notes")) {
            updates.add("notes = ?");
            args.add(text(body, "notes"));
        }
        if (body.containsKey("followUpDate")) {
            updates.add("\"followUpDate\" = ?::date");
            args.add(optionalDate(body, "followUpDate"));
        }
        saveUpdates("visitations", visitationId, updates, args);
        return visitationById(visitationId);
    }

    @DeleteMapping("/visitations/{id}")
    Map<String, String> deleteVisitation(
            @PathVariable String id, HttpServletRequest request) {
        requireRole(request, "admin", "priest");
        String visitationId = uuid(id);
        visitationById(visitationId);
        jdbc.update("DELETE FROM visitations WHERE id = ?::uuid", visitationId);
        return Map.of("message", "تم حذف سجل الافتقاد بنجاح");
    }

    @PostMapping(value = "/news", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> createNewsJson(
            @RequestBody Map<String, Object> body, HttpServletRequest request) throws IOException {
        return createNews(body, null, requireUser(request));
    }

    @PostMapping(value = "/news", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Map<String, Object> createNewsMultipart(
            @RequestParam Map<String, String> body,
            @RequestParam(value = "image", required = false) MultipartFile image,
            HttpServletRequest request) throws IOException {
        return createNews(body, image, requireUser(request));
    }

    @GetMapping("/news")
    Map<String, Object> news(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit) {
        PageArguments paging = paging(page, limit, 100);
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        var args = new ArrayList<Object>();
        if (search != null && !search.isBlank()) {
            where.append(" AND (n.content ILIKE ? OR COALESCE(n.title, '') ILIKE ?)");
            args.add("%" + search.trim() + "%");
            args.add("%" + search.trim() + "%");
        }
        String sql = newsSelect() + where
                + " ORDER BY n.\"isPinned\" DESC, n.\"createdAt\" DESC LIMIT ? OFFSET ?";
        args.add(paging.limit());
        args.add(paging.offset());
        var data = jdbc.query(sql, MAP_ROW, args.toArray());
        data.forEach(row -> row.put("author", readJson(row.get("author"))));
        long total = count("news n", where.toString(), args.subList(0, args.size() - 2));
        return Map.of(
                "data", data,
                "total", total,
                "page", paging.page(),
                "limit", paging.limit(),
                "totalPages", (int) Math.ceil((double) total / paging.limit()));
    }

    @GetMapping("/news/{id}")
    Map<String, Object> getNews(@PathVariable String id) {
        return newsById(uuid(id));
    }

    @PatchMapping("/news/{id}")
    Map<String, Object> updateNews(
            @PathVariable String id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        Map<String, Object> user = requireUser(request);
        String newsId = uuid(id);
        Map<String, Object> news = newsById(newsId);
        checkNewsPermission(news, user);
        var updates = new ArrayList<String>();
        var args = new ArrayList<Object>();
        if (body.containsKey("content")) {
            updates.add("content = ?");
            args.add(requiredText(body, "content"));
        }
        if (body.containsKey("title")) {
            updates.add("title = ?");
            args.add(text(body, "title"));
        }
        if (body.containsKey("image")) {
            updates.add("image = ?");
            args.add(text(body, "image"));
        }
        if (body.containsKey("isPinned")) {
            updates.add("\"isPinned\" = ?");
            args.add(booleanValue(body.get("isPinned"), "isPinned"));
        }
        saveUpdates("news", newsId, updates, args);
        return newsById(newsId);
    }

    @PatchMapping(value = "/news/{id}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Map<String, Object> uploadNewsImage(
            @PathVariable String id,
            @RequestParam("image") MultipartFile image,
            HttpServletRequest request) throws IOException {
        Map<String, Object> user = requireUser(request);
        String newsId = uuid(id);
        Map<String, Object> news = newsById(newsId);
        checkNewsPermission(news, user);
        String url = saveImage(image, "news");
        deleteImage((String) news.get("image"));
        jdbc.update("UPDATE news SET image = ?, \"updatedAt\" = now() WHERE id = ?::uuid",
                url, newsId);
        return newsById(newsId);
    }

    @DeleteMapping("/news/{id}/image")
    Map<String, Object> deleteNewsImage(
            @PathVariable String id, HttpServletRequest request) {
        Map<String, Object> user = requireUser(request);
        String newsId = uuid(id);
        Map<String, Object> news = newsById(newsId);
        checkNewsPermission(news, user);
        deleteImage((String) news.get("image"));
        jdbc.update("UPDATE news SET image = NULL, \"updatedAt\" = now() WHERE id = ?::uuid",
                newsId);
        return newsById(newsId);
    }

    @DeleteMapping("/news/{id}")
    Map<String, Object> deleteNews(
            @PathVariable String id, HttpServletRequest request) {
        Map<String, Object> user = requireUser(request);
        String newsId = uuid(id);
        Map<String, Object> news = newsById(newsId);
        checkNewsPermission(news, user);
        deleteImage((String) news.get("image"));
        jdbc.update("DELETE FROM news WHERE id = ?::uuid", newsId);
        return Map.of("success", true, "message", "تم حذف الخبر بنجاح");
    }

    @GetMapping("/users/{id}")
    Map<String, Object> getUser(
            @PathVariable String id, HttpServletRequest request) {
        Map<String, Object> current = requireUser(request);
        String userId = uuid(id);
        if (!userId.equals(current.get("sub")) && !"admin".equals(current.get("role"))) {
            throw forbidden("غير مسموح لك بعرض بيانات هذا المستخدم");
        }
        return userById(userId);
    }

    @PatchMapping("/users/{id}")
    @Transactional
    Map<String, Object> updateUser(
            @PathVariable String id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        Map<String, Object> current = requireUser(request);
        String userId = uuid(id);
        if (!userId.equals(current.get("sub")) && !"admin".equals(current.get("role"))) {
            throw forbidden("غير مسموح لك بتعديل بيانات هذا المستخدم");
        }
        userById(userId);
        var updates = new ArrayList<String>();
        var args = new ArrayList<Object>();
        if (body.containsKey("name")) {
            String name = requiredText(body, "name");
            if (name.length() < 2) throw badRequest("الاسم حرفين على الأقل");
            updates.add("name = ?");
            args.add(name);
        }
        if (body.containsKey("phone")) {
            String phone = requiredText(body, "phone");
            if (!phone.matches("^01[0125][0-9]{8}$")) {
                throw badRequest("رقم الموبايل المصري غير صحيح");
            }
            updates.add("phone = ?");
            args.add(phone);
        }
        if (body.containsKey("grades")) {
            Object value = body.get("grades");
            if (!(value instanceof List<?> grades)
                    || grades.stream().anyMatch(grade -> !(grade instanceof String)
                            || !GRADES.contains(grade.toString()))) {
                throw badRequest("مرحلة غير صحيحة");
            }
            String[] values = grades.stream().map(Object::toString).toArray(String[]::new);
            jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
                try (var statement = connection.prepareStatement("""
                        UPDATE users SET grades = ?::users_grades_enum[], "updatedAt" = now()
                        WHERE id = ?::uuid
                        """)) {
                    statement.setArray(1, connection.createArrayOf("users_grades_enum", values));
                    statement.setString(2, userId);
                    statement.executeUpdate();
                }
                return null;
            });
        }
        saveUpdates("users", userId, updates, args);
        syncServantFromUser(userId);
        return userById(userId);
    }

    @PatchMapping(value = "/users/{id}/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Map<String, Object> uploadUserAvatar(
            @PathVariable String id,
            @RequestParam("avatar") MultipartFile image,
            HttpServletRequest request) throws IOException {
        Map<String, Object> current = requireUser(request);
        String userId = uuid(id);
        if (!userId.equals(current.get("sub")) && !"admin".equals(current.get("role"))) {
            throw forbidden("غير مسموح لك بتعديل بيانات هذا المستخدم");
        }
        Map<String, Object> user = userById(userId);
        String url = saveImage(image, "avatars");
        deleteImage((String) user.get("avatar"));
        jdbc.update("UPDATE users SET avatar = ?, \"updatedAt\" = now() WHERE id = ?::uuid",
                url, userId);
        jdbc.update("UPDATE servants SET avatar = ?, \"updatedAt\" = now() WHERE phone = ?",
                url, user.get("phone"));
        return Map.of("message", "تم رفع الصورة بنجاح", "avatar", url);
    }

    @DeleteMapping("/users/{id}/avatar")
    Map<String, String> deleteUserAvatar(
            @PathVariable String id, HttpServletRequest request) {
        Map<String, Object> current = requireUser(request);
        String userId = uuid(id);
        if (!userId.equals(current.get("sub")) && !"admin".equals(current.get("role"))) {
            throw forbidden("غير مسموح لك بتعديل بيانات هذا المستخدم");
        }
        Map<String, Object> user = userById(userId);
        deleteImage((String) user.get("avatar"));
        jdbc.update("UPDATE users SET avatar = NULL, \"updatedAt\" = now() WHERE id = ?::uuid",
                userId);
        jdbc.update("UPDATE servants SET avatar = NULL, \"updatedAt\" = now() WHERE phone = ?",
                user.get("phone"));
        return Map.of("message", "تم حذف الصورة بنجاح");
    }

    private Map<String, Object> createNews(
            Map<String, ?> body, MultipartFile image, Map<String, Object> user) throws IOException {
        String content = requiredText(body, "content");
        String title = text(body, "title");
        boolean pinned = body.containsKey("isPinned")
                && booleanValue(body.get("isPinned"), "isPinned");
        String imageUrl = image == null || image.isEmpty()
                ? text(body, "image") : saveImage(image, "news");
        String userId = (String) user.get("sub");
        String id = jdbc.queryForObject("""
                INSERT INTO news (title, content, image, "isPinned", "authorId", "authorName", "authorRole")
                VALUES (?, ?, ?, ?, ?::uuid, ?, ?)
                RETURNING id
                """, String.class, title, content, imageUrl, pinned, userId,
                user.get("name"), user.get("role"));
        return newsById(id);
    }

    private String saveImage(MultipartFile image, String folder) throws IOException {
        if (image == null || image.isEmpty()) throw badRequest("الملف مطلوب");
        if (image.getSize() > 5L * 1024 * 1024) {
            throw badRequest("حجم الصورة لازم يكون أقل من 5 ميجا");
        }
        String originalName = image.getOriginalFilename();
        String extension = originalName == null ? ""
                : originalName.substring(originalName.lastIndexOf('.') + 1)
                        .toLowerCase(Locale.ROOT);
        if (!Set.of("jpg", "jpeg", "png", "webp").contains(extension)) {
            throw badRequest("الصور المسموح بيها: JPG, JPEG, PNG, WEBP فقط");
        }
        Path directory = uploadRoot.resolve(folder).normalize();
        Files.createDirectories(directory);
        String filename = UUID.randomUUID() + "." + extension;
        Files.copy(image.getInputStream(), directory.resolve(filename));
        return "/uploads/" + folder + "/" + filename;
    }

    private void deleteImage(String url) {
        if (url == null || !url.startsWith("/uploads/")) return;
        Path file = uploadRoot.resolve(url.substring("/uploads/".length())).normalize();
        if (!file.startsWith(uploadRoot)) return;
        try {
            Files.deleteIfExists(file);
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "تعذر حذف الصورة القديمة", exception);
        }
    }

    private Map<String, Object> classById(String id) {
        Map<String, Object> cls = jdbc.query("""
                SELECT c.*, ARRAY(SELECT ch.id::text FROM children ch
                        WHERE ch."classId" = c.id) AS "childrenIds",
                       ARRAY(SELECT sc."servantsId"::text FROM servants_classes sc
                        WHERE sc."classesId" = c.id) AS "servantIds",
                       (SELECT count(*) FROM children ch WHERE ch."classId" = c.id) AS "childrenCount"
                FROM classes c WHERE c.id = ?::uuid
                """, MAP_ROW, id).stream().findFirst()
                .orElseThrow(() -> notFound("الفصل غير موجود"));
        cls.put("children", jdbc.query("""
                SELECT * FROM children WHERE "classId" = ?::uuid ORDER BY name
                """, MAP_ROW, id));
        cls.put("servants", jdbc.query("""
                SELECT s.* FROM servants s
                JOIN servants_classes sc ON sc."servantsId" = s.id
                WHERE sc."classesId" = ?::uuid ORDER BY s.name
                """, MAP_ROW, id));
        return cls;
    }

    private String classSelect() {
        return """
                SELECT c.*, ARRAY(SELECT ch.id::text FROM children ch
                        WHERE ch."classId" = c.id) AS "childrenIds",
                       ARRAY(SELECT sc."servantsId"::text FROM servants_classes sc
                        WHERE sc."classesId" = c.id) AS "servantIds",
                       (SELECT count(*) FROM children ch WHERE ch."classId" = c.id) AS "childrenCount"
                FROM classes c
                """;
    }

    private Map<String, Object> childById(String id) {
        Map<String, Object> child = jdbc.query("""
                SELECT * FROM children WHERE id = ?::uuid
                """, MAP_ROW, id).stream().findFirst()
                .orElseThrow(() -> notFound("الطفل غير موجود"));
        child.put("class", classOrNull((String) child.get("classId")));
        return child;
    }

    private String childSelect() {
        return "SELECT ch.* FROM children ch";
    }

    private Map<String, Object> classOrNull(String id) {
        if (id == null) return null;
        return jdbc.query("SELECT * FROM classes WHERE id = ?::uuid", MAP_ROW, id)
                .stream().findFirst().orElse(null);
    }

    private Map<String, Object> servantById(String id) {
        Map<String, Object> servant = jdbc.query(
                "SELECT * FROM servants WHERE id = ?::uuid", MAP_ROW, id).stream().findFirst()
                .orElseThrow(() -> notFound("الخادم غير موجود"));
        servant.put("classes", classesForServant(id));
        return servant;
    }

    private List<Map<String, Object>> classesForServant(String id) {
        return jdbc.query("""
                SELECT c.* FROM classes c
                JOIN servants_classes sc ON sc."classesId" = c.id
                WHERE sc."servantsId" = ?::uuid ORDER BY c.name
                """, MAP_ROW, id);
    }

    private String servantSelect() {
        return "SELECT s.* FROM servants s";
    }

    private Map<String, Object> attendanceById(String id) {
        Map<String, Object> record = jdbc.query("""
                SELECT a.*,
                       (SELECT to_jsonb(ch.*) FROM children ch WHERE ch.id = a."childId") AS child,
                       (SELECT to_jsonb(c.*) FROM classes c WHERE c.id = a."classId") AS class
                FROM attendance a WHERE a.id = ?::uuid
                """, MAP_ROW, id).stream().findFirst()
                .orElseThrow(() -> notFound("سجل الحضور غير موجود"));
        record.put("child", readJson(record.get("child")));
        record.put("class", readJson(record.get("class")));
        return record;
    }

    private Map<String, Object> visitationById(String id) {
        Map<String, Object> record = jdbc.query("""
                SELECT v.*,
                       (SELECT to_jsonb(ch.*) FROM children ch WHERE ch.id = v."childId") AS child,
                       (SELECT to_jsonb(s.*) FROM servants s WHERE s.id = v."servantId") AS servant
                FROM visitations v WHERE v.id = ?::uuid
                """, MAP_ROW, id).stream().findFirst()
                .orElseThrow(() -> notFound("سجل الافتقاد غير موجود"));
        record.put("child", readJson(record.get("child")));
        record.put("servant", readJson(record.get("servant")));
        return record;
    }

    private Map<String, Object> newsById(String id) {
        Map<String, Object> news = jdbc.query(
                newsSelect() + " WHERE n.id = ?::uuid", MAP_ROW, id).stream()
                .findFirst().orElseThrow(() -> notFound("الخبر غير موجود"));
        news.put("author", readJson(news.get("author")));
        return news;
    }

    private String newsSelect() {
        return """
                SELECT n.*, (SELECT to_jsonb(u.*) - 'password' FROM users u
                        WHERE u.id = n."authorId") AS author
                FROM news n
                """;
    }

    private Map<String, Object> userById(String id) {
        return jdbc.query("""
                SELECT id, name, email, "emailVerified", phone, role, grades, avatar,
                       "createdAt", "updatedAt"
                FROM users WHERE id = ?::uuid
                """, MAP_ROW, id).stream().findFirst()
                .orElseThrow(() -> notFound("المستخدم غير موجود"));
    }

    private void replaceServantClasses(String servantId, Object value) {
        if (value == null) return;
        if (!(value instanceof List<?> classIds)) throw badRequest("قائمة الفصول غير صالحة");
        jdbc.update("DELETE FROM servants_classes WHERE \"servantsId\" = ?::uuid", servantId);
        for (Object item : classIds) {
            String classId = uuid(String.valueOf(item));
            classById(classId);
            jdbc.update("""
                    INSERT INTO servants_classes ("servantsId", "classesId")
                    VALUES (?::uuid, ?::uuid) ON CONFLICT DO NOTHING
                    """, servantId, classId);
        }
    }

    private void syncServantFromUser(String userId) {
        Map<String, Object> user = jdbc.query(
                "SELECT * FROM users WHERE id = ?::uuid", MAP_ROW, userId)
                .stream().findFirst().orElseThrow(() -> notFound("المستخدم غير موجود"));
        String role = String.valueOf(user.get("role"));
        List<?> grades = user.get("grades") instanceof List<?> list ? list : List.of();
        String servantRole = "admin".equals(role) ? "مشرف" : "خادم";
        List<Map<String, Object>> existing = jdbc.query("""
                SELECT id, role FROM servants WHERE phone = ? OR email = ? LIMIT 1
                """, MAP_ROW, user.get("phone"), user.get("email"));
        String servantId;
        if (existing.isEmpty()) {
            servantId = jdbc.queryForObject("""
                    INSERT INTO servants (name, phone, email, role, avatar)
                    VALUES (?, ?, ?, ?::servants_role_enum, ?) RETURNING id
                    """, String.class, user.get("name"), user.get("phone"), user.get("email"),
                    servantRole, user.get("avatar"));
        } else {
            Map<String, Object> servant = existing.getFirst();
            servantId = String.valueOf(servant.get("id"));
            String currentRole = String.valueOf(servant.get("role"));
            if (!Set.of("خادم", "خادم فقرة عامة").contains(currentRole)) {
                servantRole = currentRole;
            }
            jdbc.update("""
                    UPDATE servants SET name = ?, phone = ?, email = ?, role = ?::servants_role_enum,
                        avatar = ?, "updatedAt" = now() WHERE id = ?::uuid
                    """, user.get("name"), user.get("phone"), user.get("email"), servantRole,
                    user.get("avatar"), servantId);
        }
        jdbc.update("DELETE FROM servants_classes WHERE \"servantsId\" = ?::uuid", servantId);
        if (!Set.of("admin", "priest").contains(role) && !grades.isEmpty()) {
            String[] gradeValues = grades.stream().map(Object::toString).toArray(String[]::new);
            jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
                try (var statement = connection.prepareStatement("""
                        INSERT INTO servants_classes ("servantsId", "classesId")
                        SELECT ?::uuid, id FROM classes WHERE grade = ANY (?::classes_grade_enum[])
                        ON CONFLICT DO NOTHING
                        """)) {
                    statement.setString(1, servantId);
                    statement.setArray(2, connection.createArrayOf(
                            "classes_grade_enum", gradeValues));
                    statement.executeUpdate();
                }
                return null;
            });
        }
    }

    private Object readJson(Object value) {
        if (value == null) return null;
        String json = value.toString();
        if (!json.startsWith("{") && !json.startsWith("[")) return value;
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Database returned malformed JSON", exception);
        }
    }

    private void checkNewsPermission(Map<String, Object> news, Map<String, Object> user) {
        if (!Set.of("admin", "priest").contains(user.get("role"))
                && !String.valueOf(user.get("sub")).equals(String.valueOf(news.get("authorId")))) {
            throw forbidden("غير مسموح لك بتعديل أو حذف هذا الخبر");
        }
    }

    private Map<String, Object> requireUser(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ") || header.substring(7).isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "لازم تسجّل الدخول الأول");
        }
        try {
            return auth.authenticatedClaims(header.substring(7).trim());
        } catch (AuthException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "التوكن غير صالح أو منتهي الصلاحية");
        }
    }

    private Map<String, Object> requireRole(HttpServletRequest request, String... roles) {
        Map<String, Object> user = requireUser(request);
        if (!List.of(roles).contains(user.get("role"))) {
            throw forbidden("غير مسموح لك بتنفيذ هذا الإجراء");
        }
        return user;
    }

    private Map<String, Object> page(List<Map<String, Object>> data, long total, PageArguments paging) {
        return Map.of("data", data, "meta", Map.of(
                "total", total,
                "page", paging.page(),
                "limit", paging.limit(),
                "totalPages", (int) Math.ceil((double) total / paging.limit())));
    }

    private long count(String from, String where, List<Object> args) {
        Long result = jdbc.queryForObject("SELECT count(*) FROM " + from + where,
                Long.class, args.toArray());
        return result == null ? 0 : result;
    }

    private void saveUpdates(
            String table, String id, List<String> updates, List<Object> args) {
        if (updates.isEmpty()) return;
        args.add(id);
        jdbc.update("UPDATE " + table + " SET " + String.join(", ", updates)
                + ", \"updatedAt\" = now() WHERE id = ?::uuid", args.toArray());
    }

    private void addDateFilter(
            StringBuilder where, List<Object> args, String column, String value, String operator) {
        if (value != null && !value.isBlank()) {
            where.append(" AND ").append(column).append(' ').append(operator).append(" ?::date");
            args.add(parseDate(value));
        }
    }

    private PageArguments paging(int page, int limit, int maximum) {
        if (page < 1 || limit < 1 || limit > maximum) {
            throw badRequest("رقم الصفحة أو عدد العناصر غير صالح");
        }
        return new PageArguments(page, limit, (page - 1) * limit);
    }

    private int nonNegative(int value, String field) {
        if (value < 0) throw badRequest(field + " يجب ألا يقل عن صفر");
        return value;
    }

    private String uuid(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            if (parsed.version() != 4) throw new IllegalArgumentException();
            return parsed.toString();
        } catch (RuntimeException exception) {
            throw badRequest("معرّف غير صالح");
        }
    }

    private String optionalUuid(Map<String, ?> body, String key) {
        Object value = body.get(key);
        return value == null || value.toString().isBlank() ? null : uuid(value.toString());
    }

    private String date(Map<String, ?> body, String key, boolean required) {
        Object value = body.get(key);
        if (value == null || value.toString().isBlank()) {
            if (required) throw badRequest("التاريخ مطلوب");
            return null;
        }
        return parseDate(value.toString()).toString();
    }

    private String optionalDate(Map<String, ?> body, String key) {
        return date(body, key, false);
    }

    private LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException exception) {
            throw badRequest("التاريخ لازم يكون بصيغة YYYY-MM-DD");
        }
    }

    private String requiredText(Map<String, ?> body, String key) {
        Object value = body.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw badRequest("الحقل " + key + " مطلوب");
        }
        return text.trim();
    }

    private String text(Map<String, ?> body, String key) {
        return nullableText(body.get(key));
    }

    private String nullableText(Object value) {
        return value == null ? null : value.toString();
    }

    private String oneOf(
            Map<String, ?> body, String key, Set<String> allowed, boolean required) {
        Object value = body.get(key);
        if (value == null && !required) return null;
        return oneOf(value, allowed, key);
    }

    private String oneOf(Object value, Set<String> allowed, String field) {
        String text = nullableText(value);
        if (text == null || !allowed.contains(text)) {
            throw badRequest("قيمة الحقل " + field + " غير صالحة");
        }
        return text;
    }

    private boolean booleanValue(Object value, String field) {
        if (value instanceof Boolean result) return result;
        if ("true".equals(value)) return true;
        if ("false".equals(value)) return false;
        throw badRequest("قيمة الحقل " + field + " غير صالحة");
    }

    private String academicYear() {
        int year = LocalDate.now().getYear();
        return year + "-" + (year + 1);
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private ResponseStatusException forbidden(String message) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }

    private record PageArguments(int page, int limit, int offset) {}
}
