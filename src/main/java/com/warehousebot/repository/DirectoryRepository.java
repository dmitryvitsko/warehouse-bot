package com.warehousebot.repository;

import com.warehousebot.domain.DirectoryContact;
import com.warehousebot.infrastructure.db.Database;

import java.util.List;

/** Таблица directory_contacts. */
public final class DirectoryRepository {

    private final Database db;

    public DirectoryRepository(Database db) {
        this.db = db;
    }

    public List<DirectoryContact> findAll() {
        return db.query("SELECT id, category, contact_text FROM directory_contacts ORDER BY id",
                rs -> new DirectoryContact(rs.getInt("id"), rs.getInt("category"), rs.getString("contact_text")));
    }

    public void add(int category, String text, long addedBy) {
        db.update("INSERT INTO directory_contacts (category, contact_text, added_by) VALUES (?, ?, ?)", category, text, addedBy);
    }

    public void delete(int id) {
        db.update("DELETE FROM directory_contacts WHERE id = ?", id);
    }
}
